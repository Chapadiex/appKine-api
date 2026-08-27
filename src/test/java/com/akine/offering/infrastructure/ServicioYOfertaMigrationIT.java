package com.akine.offering.infrastructure;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.akine.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V24 contra MySQL real (AKINE-02.06, tarea 1): {@code servicio} y
 * {@code oferta_servicio_consultorio}.
 *
 * <p>Las dos tablas todavia no tienen entidad, servicio, controller ni permiso propio: eso llega
 * en las tareas 2 y 3 de la etapa. Lo unico que existe hoy es el ESQUEMA, y se verifica
 * insertando directo contra una base real, igual que hicieron {@code FeriadoMigrationIT} y
 * {@code DisponibilidadMigrationIT} en 02.04.
 *
 * <p>Lo que estos tests protegen, en una linea cada uno: que {@code servicio} sea global de
 * verdad y sin el centinela {@code owner_key} que no le corresponde (ADR-0023), que la Oferta
 * lleve tenant y lo lleve en todos sus indices (ADR-0004), y que los cuatro CHECK que la etapa
 * discutio —capacidad grupal, precio con moneda, vigencia exclusiva y nombre comercial unico
 * entre vigentes— existan en la base y no solo en el diseño.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ServicioYOfertaMigrationIT {

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;

	private JdbcTemplate jdbc() {
		if (jdbc == null) {
			jdbc = new JdbcTemplate(dataSource);
		}
		return jdbc;
	}

	// =================================================================================
	// servicio — global, sin organization_id y sin owner_key
	// =================================================================================

	@Test
	@DisplayName("servicio no tiene organization_id")
	void servicio_no_tiene_organization_id() {
		// La quinta excepcion a ADR-0004, declarada en ADR-0023: un Servicio es catalogo global
		// (RN-M27-001) y la seccion 30.6 no lista organizationId entre sus campos. Lo que si es de
		// un centro concreto —como lo presta— vive en oferta_servicio_consultorio.
		assertThat(columnasDe("servicio"))
				.as("servicio no puede llevar organization_id: es global, y agregarlo reintroduce "
						+ "en el catalogo la configuracion por centro que ya vive en la Oferta")
				.doesNotContain("organization_id");

		// El contrapeso, para que este test no pase por el motivo equivocado: la tabla existe y
		// tiene las columnas que la hacen un catalogo. Sin esto, un `servicio` que nunca se creo
		// tambien "no tiene organization_id".
		assertThat(columnasDe("servicio"))
				.contains("codigo", "nombre", "naturaleza", "modalidad_default", "active");
	}

	@Test
	@DisplayName("servicio no tiene owner_key, pero si deleted_key")
	void servicio_no_tiene_owner_key() {
		// La parte facil de copiar mal de ADR-0021: `owner_key` NO es "la forma de las tablas
		// globales", es la forma de las tablas con DOS poblaciones. especialidad y practica (V20)
		// lo llevan porque conviven filas globales y filas de tenant, y en MySQL varios NULL no
		// colisionan en un unique. servicio tiene una sola poblacion: el centinela no distinguiria
		// nada y seria una columna generada decorativa.
		assertThat(columnasDe("servicio"))
				.as("servicio no lleva owner_key: sin segunda poblacion el centinela no protege nada")
				.doesNotContain("owner_key");

		// deleted_key es un asunto INDEPENDIENTE y si hace falta: RF-M27-002 prohibe el borrado
		// fisico, asi que un codigo dado de baja tiene que poder reusarse (V18/V19/V20).
		assertThat(columnasDe("servicio"))
				.as("servicio si lleva deleted_key: la baja es logica y el codigo se debe poder reusar")
				.contains("deleted_key");
	}

	// =================================================================================
	// oferta_servicio_consultorio — con tenant, como espacio y no como el catalogo
	// =================================================================================

	@Test
	@DisplayName("oferta_servicio_consultorio lleva organization_id NOT NULL")
	void oferta_lleva_organization_id_not_null() {
		String esNullable = jdbc().queryForObject("""
				SELECT is_nullable FROM information_schema.columns
				 WHERE table_schema = DATABASE()
				   AND table_name = 'oferta_servicio_consultorio'
				   AND column_name = 'organization_id'
				""", String.class);

		assertThat(esNullable)
				.as("no existe la oferta global: toda oferta pertenece a una sede real, asi que "
						+ "organization_id es NOT NULL (ADR-0004, AGENT.md seccion 5)")
				.isEqualTo("NO");

		// Y por lo tanto NO lleva owner_key: no tiene el problema que owner_key resuelve, porque
		// ninguna fila puede tener organization_id NULL.
		assertThat(columnasDe("oferta_servicio_consultorio"))
				.as("con organization_id NOT NULL no hay NULL que colisionar: owner_key sobra")
				.doesNotContain("owner_key");
	}

	@Test
	@DisplayName("todos los indices declarados de oferta_servicio_consultorio empiezan por organization_id")
	void todos_los_indices_declarados_de_oferta_empiezan_por_organization_id() {
		// Se excluye la PRIMARY KEY, que es (id) a proposito, y los indices de soporte que InnoDB
		// crea SOLO para las FK. Estos ultimos NO se filtran por el prefijo `fk_` de su nombre: se
		// preguntan a information_schema.table_constraints, que es un hecho del catalogo y no un
		// acuerdo de nomenclatura. Es lo que cerro 02.04 (ver DisponibilidadMigrationIT): el dia
		// que alguien declare un indice diseñado llamado `fk_algo`, el filtro por prefijo empieza a
		// tapar cosas en silencio, y este test existe justamente para que un indice sin alcance de
		// tenant no pase inadvertido.
		List<Map<String, Object>> primeraColumnaPorIndice = jdbc().queryForList("""
				SELECT s.index_name, s.column_name
				  FROM information_schema.statistics s
				 WHERE s.table_schema = DATABASE()
				   AND s.table_name = 'oferta_servicio_consultorio'
				   AND s.index_name <> 'PRIMARY'
				   AND s.seq_in_index = 1
				   AND NOT EXISTS (
				       SELECT 1 FROM information_schema.table_constraints tc
				        WHERE tc.table_schema = s.table_schema
				          AND tc.table_name = s.table_name
				          AND tc.constraint_name = s.index_name
				          AND tc.constraint_type = 'FOREIGN KEY')
				""");

		// El unique de nombre comercial y los dos indices de listado. Si el filtro de FK se pasara
		// de largo y vaciara la lista, el for de abajo no verificaria nada.
		assertThat(primeraColumnaPorIndice)
				.as("la tabla tiene que tener el unique de nombre comercial y sus dos indices de "
						+ "listado ademas de la PK")
				.hasSize(3);

		for (Map<String, Object> fila : primeraColumnaPorIndice) {
			assertThat(fila.get("column_name"))
					.as("oferta_servicio_consultorio.%s no empieza por organization_id: un unique o "
							+ "indice sin la columna de tenant es un bug de aislamiento aunque hoy "
							+ "consultorio_id ya determine el tenant por la FK (AGENT.md seccion 5)",
							fila.get("index_name"))
					.isEqualTo("organization_id");
		}
	}

	// =================================================================================
	// capacidad: GRUPAL exige > 1, INDIVIDUAL admite 1
	// =================================================================================

	@Test
	@DisplayName("una oferta GRUPAL de capacidad 1 es rechazada")
	void una_oferta_grupal_de_capacidad_uno_es_rechazada() {
		Fixture fixture = crearFixture();

		// DECISION REVISABLE de la etapa: RF-M27-003 dice "mayor a cero", pero una grupal de
		// capacidad 1 es una individual mal rotulada, y el motor de inscripciones de F2 la trataria
		// como un grupo de una persona.
		assertThatThrownBy(() -> insertarOferta(fixture, nombre("grupal-1"), "GRUPAL", 1))
				.as("modalidad GRUPAL con capacidad 1 tiene que violar ck_oferta_grupal_capacidad")
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_oferta_grupal_capacidad");

		// Y el borde de arriba: 2 es el minimo aceptable para una grupal. Sin esto, un CHECK que
		// dijera `capacidad > 5` tambien pasaria el assert de arriba.
		assertThatCode(() -> insertarOferta(fixture, nombre("grupal-2"), "GRUPAL", 2))
				.as("capacidad 2 es el minimo de una oferta grupal y tiene que entrar")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("una oferta INDIVIDUAL de capacidad 1 es aceptada")
	void una_oferta_individual_de_capacidad_uno_es_aceptada() {
		Fixture fixture = crearFixture();

		String nombreComercial = nombre("individual-1");
		insertarOferta(fixture, nombreComercial, "INDIVIDUAL", 1);

		assertThat(contarOfertas(fixture, nombreComercial))
				.as("el CHECK de capacidad grupal no puede alcanzar a las ofertas individuales: "
						+ "capacidad 1 es lo normal en una atencion uno a uno")
				.isEqualTo(1L);

		// El otro borde: 0 no es "menos cupo", es una baja sin motivo ni rastro, y la baja ya tiene
		// sus tres columnas. Mismo criterio que espacio.capacidad en V19.
		assertThatThrownBy(() -> insertarOferta(fixture, nombre("individual-0"), "INDIVIDUAL", 0))
				.as("capacidad 0 tiene que violar ck_oferta_capacidad_positiva")
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_oferta_capacidad_positiva");
	}

	// =================================================================================
	// precio_base y moneda viajan juntos o no viajan
	// =================================================================================

	@Test
	@DisplayName("un precio sin moneda es rechazado")
	void un_precio_sin_moneda_es_rechazado() {
		Fixture fixture = crearFixture();

		assertThatThrownBy(() -> insertarOfertaConPrecio(fixture, nombre("precio-solo"),
				new java.math.BigDecimal("4500.00"), null))
				.as("4500 no significa nada sin saber de que moneda es: tiene que violar "
						+ "ck_oferta_precio_con_moneda")
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_oferta_precio_con_moneda");
	}

	@Test
	@DisplayName("una moneda sin precio es rechazada")
	void una_moneda_sin_precio_es_rechazada() {
		Fixture fixture = crearFixture();

		assertThatThrownBy(() -> insertarOfertaConPrecio(fixture, nombre("moneda-sola"), null, "ARS"))
				.as("una moneda sin importe es un dato a medias: tiene que violar "
						+ "ck_oferta_precio_con_moneda")
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_oferta_precio_con_moneda");

		// Las dos combinaciones validas, para que el CHECK no sea "siempre falso": ninguno de los
		// dos, y los dos juntos. Sin precio declarado es un estado real mientras M15/M16/M18 no
		// existan (RN-M27-006).
		assertThatCode(() -> {
			insertarOfertaConPrecio(fixture, nombre("sin-precio"), null, null);
			insertarOfertaConPrecio(fixture, nombre("con-precio"),
					new java.math.BigDecimal("4500.00"), "ARS");
		})
				.as("ni precio ni moneda, y precio con moneda, son las dos formas validas")
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// vigencia_hasta es EXCLUSIVA
	// =================================================================================

	@Test
	@DisplayName("vigencia_hasta igual a vigencia_desde es rechazada")
	void vigencia_hasta_igual_a_vigencia_desde_es_rechazada() {
		Fixture fixture = crearFixture();

		// El limite superior es EXCLUSIVO, igual que valid_until en V19 y vigencia_hasta en V23:
		// una oferta con vigencia_hasta = vigencia_desde seria reservable durante cero dias. Si el
		// CHECK dijera ">=" en vez de ">", esa fila entraria y no ofreceria un solo turno, sin
		// romper nada visible.
		assertThatThrownBy(() -> insertarOfertaConVigencia(fixture, nombre("vigencia-cero"), 0))
				.as("vigencia_hasta == vigencia_desde tiene que violar ck_oferta_vigencia_coherente")
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_oferta_vigencia_coherente");

		assertThatThrownBy(() -> insertarOfertaConVigencia(fixture, nombre("vigencia-negativa"), -1))
				.as("una ventana que termina antes de empezar tampoco es una ventana")
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("ck_oferta_vigencia_coherente");

		assertThatCode(() -> insertarOfertaConVigencia(fixture, nombre("vigencia-un-dia"), 1))
				.as("un dia de vigencia es la ventana valida mas corta y tiene que entrar")
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// nombre comercial unico entre las ofertas VIGENTES de la sede
	// =================================================================================

	@Test
	@DisplayName("dos ofertas activas de la misma sede no pueden compartir nombre comercial")
	void dos_ofertas_activas_de_la_misma_sede_no_pueden_compartir_nombre_comercial() {
		Fixture fixture = crearFixture();

		String nombreComercial = nombre("kinesio-vespertina");
		insertarOferta(fixture, nombreComercial, "INDIVIDUAL", 1);

		assertThatThrownBy(() -> insertarOferta(fixture, nombreComercial, "INDIVIDUAL", 1))
				.as("uk_oferta_sede_nombre_vigente tiene que impedir dos ofertas activas homonimas "
						+ "en la misma sede: es el caso que importa, y el reflejo de poner "
						+ "deleted_at en el unique lo dejaria pasar porque varios NULL no colisionan")
				.isInstanceOf(DataIntegrityViolationException.class);

		// El mismo nombre en OTRA sede de la misma organizacion si entra: el unique es por sede.
		long otraSede = insertarConsultorio(fixture.organizationId(), UUID.randomUUID().toString()
				.substring(0, 8));
		assertThatCode(() -> insertarOferta(
				new Fixture(fixture.organizationId(), otraSede, fixture.servicioId()),
				nombreComercial, "INDIVIDUAL", 1))
				.as("el alcance del unique es la sede, no la organizacion")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el nombre comercial se puede reusar despues de una baja")
	void el_nombre_comercial_se_puede_reusar_despues_de_una_baja() {
		Fixture fixture = crearFixture();

		String nombreComercial = nombre("pilates-terapeutico");
		insertarOferta(fixture, nombreComercial, "INDIVIDUAL", 1);
		darDeBaja(fixture, nombreComercial);

		// RN-M27-007: la oferta inactiva conserva sus historicos. El centinela de fecha permite
		// que la sede vuelva a ofrecer el mismo nombre sin borrar la fila vieja.
		assertThatCode(() -> insertarOferta(fixture, nombreComercial, "INDIVIDUAL", 1))
				.as("tras la baja logica el nombre comercial se tiene que poder reusar: el "
						+ "deleted_key de la fila dada de baja ya no es el centinela 1970-01-01")
				.doesNotThrowAnyException();

		assertThat(contarOfertas(fixture, nombreComercial))
				.as("la fila vieja NO se borra: quedan dos, una activa y una dada de baja")
				.isEqualTo(2L);
	}

	// =================================================================================
	// Helpers
	// =================================================================================

	private record Fixture(long organizationId, long consultorioId, long servicioId) {
	}

	private List<String> columnasDe(String tabla) {
		return jdbc().queryForList("""
				SELECT column_name FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name = ?
				""", String.class, tabla);
	}

	private String nombre(String prefijo) {
		return prefijo + "-" + UUID.randomUUID().toString().substring(0, 8);
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertarOrganization(sufijo);
		long consultorioId = insertarConsultorio(organizationId, sufijo);
		long servicioId = insertarServicio(sufijo);

		return new Fixture(organizationId, consultorioId, servicioId);
	}

	private long insertarOrganization(String sufijo) {
		String slug = "oferta-" + sufijo;
		jdbc().update("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Oferta Sintetica " + sufijo, slug);
		return jdbc().queryForObject(
				"SELECT id FROM organization WHERE slug = ?", Long.class, slug);
	}

	private long insertarConsultorio(long organizationId, String sufijo) {
		String name = "Sede Sintetica " + sufijo;
		jdbc().update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, name);
		return jdbc().queryForObject(
				"SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, organizationId, name);
	}

	private long insertarServicio(String sufijo) {
		String codigo = "SRV-" + sufijo;
		jdbc().update("""
				INSERT INTO servicio (codigo, nombre, descripcion, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default,
				                      genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'Servicio sintetico de prueba', 'CLINICO', 'INDIVIDUAL', 1, 1,
				        1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", codigo, "Servicio Sintetico " + sufijo);
		return jdbc().queryForObject(
				"SELECT id FROM servicio WHERE codigo = ?", Long.class, codigo);
	}

	private void insertarOferta(Fixture fixture, String nombreComercial, String modalidad,
			int capacidad) {
		jdbc().update("""
				INSERT INTO oferta_servicio_consultorio
				    (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				     duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				     genera_registro_clinico, requiere_profesional, requiere_espacio,
				     vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 45, ?, 0, 1, 1, 1, 1, CURRENT_DATE(), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), fixture.servicioId(),
				nombreComercial, modalidad, capacidad);
	}

	private void insertarOfertaConPrecio(Fixture fixture, String nombreComercial,
			java.math.BigDecimal precioBase, String moneda) {
		jdbc().update("""
				INSERT INTO oferta_servicio_consultorio
				    (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				     duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				     requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				     requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 45, 1, ?, ?, 0, 1, 1, 1, 1, CURRENT_DATE(), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), fixture.servicioId(),
				nombreComercial, precioBase, moneda);
	}

	private void insertarOfertaConVigencia(Fixture fixture, String nombreComercial, int diasHasta) {
		jdbc().update("""
				INSERT INTO oferta_servicio_consultorio
				    (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				     duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				     genera_registro_clinico, requiere_profesional, requiere_espacio,
				     vigencia_desde, vigencia_hasta, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 45, 1, 0, 1, 1, 1, 1,
				        CURRENT_DATE(), DATE_ADD(CURRENT_DATE(), INTERVAL ? DAY), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), fixture.servicioId(),
				nombreComercial, diasHasta);
	}

	private void darDeBaja(Fixture fixture, String nombreComercial) {
		jdbc().update("""
				UPDATE oferta_servicio_consultorio
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6),
				       deactivation_reason = 'Baja sintetica de prueba',
				       updated_at = UTC_TIMESTAMP(6)
				 WHERE organization_id = ? AND consultorio_id = ? AND nombre_comercial = ?
				""", fixture.organizationId(), fixture.consultorioId(), nombreComercial);
	}

	private Long contarOfertas(Fixture fixture, String nombreComercial) {
		return jdbc().queryForObject("""
				SELECT COUNT(*) FROM oferta_servicio_consultorio
				 WHERE organization_id = ? AND consultorio_id = ? AND nombre_comercial = ?
				""", Long.class, fixture.organizationId(), fixture.consultorioId(), nombreComercial);
	}
}
