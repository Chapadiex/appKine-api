package com.akine.offering.infrastructure;

import java.util.List;
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
 * V28 contra MySQL real (AKINE-02.07): las dos tablas de habilitacion.
 *
 * <p>Verifica que las garantias que el diseno discutio existan <b>en la base</b> y no solo en el
 * documento. Mismo criterio que {@code ServicioYOfertaMigrationIT} en 02.06: se inserta directo
 * contra una base real, porque un CHECK que la aplicacion respeta pero la base no tiene deja de
 * proteger en cuanto alguien escribe por otro camino —una migracion de datos, un fix a mano—.
 *
 * <p>Lo que cada test protege, en una linea: que las dos tablas lleven tenant y lo lleven en sus
 * uniques (ADR-0004, y aca <b>sin excepcion que declarar</b>, a diferencia de {@code servicio});
 * que un recurso no se pueda habilitar dos veces vigente para la misma oferta; que el de una
 * habilitacion dada de baja SI se pueda volver a habilitar; y que los dos CHECK —coherencia de la
 * baja y vigencia exclusiva— esten puestos.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class HabilitacionesMigrationIT {

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
	// Tenant
	// =================================================================================

	@Test
	@DisplayName("las dos tablas llevan organization_id NOT NULL: no hay habilitacion global")
	void las_dos_tablas_llevan_tenant() {
		// A diferencia de `servicio`, aca NO hay excepcion de ADR-0023 que declarar: una
		// habilitacion pertenece siempre a un tenant, porque la oferta pertenece a uno.
		for (String tabla : List.of("oferta_profesional_habilitado", "oferta_espacio_habilitado")) {
			String nullable = jdbc().queryForObject("""
					SELECT is_nullable FROM information_schema.columns
					 WHERE table_schema = DATABASE() AND table_name = ?
					   AND column_name = 'organization_id'
					""", String.class, tabla);

			assertThat(nullable)
					.as("%s tiene que llevar organization_id NOT NULL", tabla)
					.isEqualTo("NO");
		}
	}

	@Test
	@DisplayName("todo unique e indice de las dos tablas empieza por organization_id")
	void los_indices_empiezan_por_el_tenant() {
		// Si el tenant no va primero, el indice no sirve para acotar por organizacion y la
		// consulta termina escaneando filas de otros centros para despues descartarlas.
		for (String tabla : List.of("oferta_profesional_habilitado", "oferta_espacio_habilitado")) {
			// Se excluyen los indices que MySQL crea SOLA para sostener cada FK: no los declara la
			// migracion y exigirles el tenant adelante seria pedirle a la base algo que no depende
			// de nosotros. Mismo filtro que ServicioYOfertaMigrationIT.
			List<String> primeras = jdbc().queryForList("""
					SELECT DISTINCT s.column_name
					  FROM information_schema.statistics s
					 WHERE s.table_schema = DATABASE() AND s.table_name = ?
					   AND s.seq_in_index = 1 AND s.index_name <> 'PRIMARY'
					   AND NOT EXISTS (
					       SELECT 1 FROM information_schema.table_constraints tc
					        WHERE tc.table_schema = s.table_schema
					          AND tc.table_name = s.table_name
					          AND tc.constraint_name = s.index_name
					          AND tc.constraint_type = 'FOREIGN KEY')
					""", String.class, tabla);

			// Si el filtro de FK se pasara de largo y vaciara la lista, el assert de abajo pasaria
			// sin verificar nada.
			assertThat(primeras)
					.as("%s tiene que conservar su unique y sus indices de listado", tabla)
					.isNotEmpty();
			assertThat(primeras)
					.as("todo indice declarado de %s tiene que empezar por organization_id", tabla)
					.containsOnly("organization_id");
		}
	}

	// =================================================================================
	// Unicidad y reuso
	// =================================================================================

	@Test
	@DisplayName("un profesional no se puede habilitar dos veces VIGENTE para la misma oferta")
	void no_se_puede_habilitar_dos_veces_al_mismo_profesional() {
		Fixture fixture = crearFixture();

		habilitarProfesional(fixture);

		assertThatThrownBy(() -> habilitarProfesional(fixture))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("el de una habilitacion dada de baja SI se puede volver a habilitar")
	void una_habilitacion_cerrada_libera_el_recurso() {
		// Es lo que hace `deleted_key`: sin ese centinela, dos filas con deleted_at NULL no
		// colisionarian —en MySQL varios NULL no colisionan en un unique— y con deleted_at real
		// tampoco se podria reabrir nunca.
		Fixture fixture = crearFixture();

		habilitarProfesional(fixture);
		jdbc().update("""
				UPDATE oferta_profesional_habilitado
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6),
				       deactivation_reason = 'dejo de atender esto'
				 WHERE oferta_id = ? AND membership_id = ?
				""", fixture.ofertaId(), fixture.membershipId());

		assertThatCode(() -> habilitarProfesional(fixture)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("un espacio tampoco se puede habilitar dos veces vigente para la misma oferta")
	void no_se_puede_habilitar_dos_veces_el_mismo_espacio() {
		Fixture fixture = crearFixture();

		habilitarEspacio(fixture);

		assertThatThrownBy(() -> habilitarEspacio(fixture))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// =================================================================================
	// Los dos CHECK
	// =================================================================================

	@Test
	@DisplayName("una baja sin motivo no entra: la auditoria no puede quedar sin poder explicarla")
	void la_baja_exige_motivo() {
		Fixture fixture = crearFixture();
		habilitarProfesional(fixture);

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE oferta_profesional_habilitado
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6)
				 WHERE oferta_id = ? AND membership_id = ?
				""", fixture.ofertaId(), fixture.membershipId()))
				.as("una baja sin motivo tiene que violar ck_oph_baja_coherente. El CHECK de "
						+ "MySQL llega como UncategorizedSQLException y no como violacion de "
						+ "integridad, al reves que el UNIQUE")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una ventana de vigencia de largo cero no entra: el fin es EXCLUSIVO")
	void la_vigencia_exige_un_fin_posterior_al_inicio() {
		// Con fin exclusivo, valid_until igual a valid_from no habilita nada, y una fila que no
		// habilita nada es una configuracion que alguien va a leer como si habilitara.
		Fixture fixture = crearFixture();

		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO oferta_espacio_habilitado
				    (organization_id, consultorio_id, oferta_id, espacio_id,
				     valid_from, valid_until, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId(),
				fixture.espacioId()))
				.as("una ventana de largo cero tiene que violar ck_oeh_vigencia_coherente")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	// =================================================================================
	// Helpers
	// =================================================================================

	private record Fixture(
			long organizationId, long consultorioId, long servicioId, long ofertaId,
			long membershipId, long espacioId, long accountId) {
	}

	private void habilitarProfesional(Fixture fixture) {
		jdbc().update("""
				INSERT INTO oferta_profesional_habilitado
				    (organization_id, consultorio_id, oferta_id, membership_id,
				     valid_from, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId(),
				fixture.membershipId());
	}

	private void habilitarEspacio(Fixture fixture) {
		jdbc().update("""
				INSERT INTO oferta_espacio_habilitado
				    (organization_id, consultorio_id, oferta_id, espacio_id,
				     valid_from, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, UTC_TIMESTAMP(6), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", fixture.organizationId(), fixture.consultorioId(), fixture.ofertaId(),
				fixture.espacioId());
	}

	private Fixture crearFixture() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);

		long organizationId = insertarOrganization(sufijo);
		long consultorioId = insertarConsultorio(organizationId, sufijo);
		long servicioId = insertarServicio(sufijo);
		long ofertaId = insertarOferta(organizationId, consultorioId, servicioId, sufijo);
		long accountId = insertarCuenta(sufijo);
		long membershipId = insertarMembership(organizationId, accountId);
		long espacioId = insertarEspacio(organizationId, consultorioId, sufijo);

		return new Fixture(organizationId, consultorioId, servicioId, ofertaId, membershipId,
				espacioId, accountId);
	}

	private long insertarOrganization(String sufijo) {
		String slug = "habilitacion-" + sufijo;
		jdbc().update("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Habilitacion Sintetica " + sufijo, slug);
		return jdbc().queryForObject(
				"SELECT id FROM organization WHERE slug = ?", Long.class, slug);
	}

	private long insertarConsultorio(long organizationId, String sufijo) {
		String name = "Sede " + sufijo;
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
		String codigo = "HAB-" + sufijo;
		jdbc().update("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default,
				                      genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'GRUPAL', 1, 1, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", codigo, "Servicio Sintetico " + sufijo);
		return jdbc().queryForObject(
				"SELECT id FROM servicio WHERE codigo = ?", Long.class, codigo);
	}

	private long insertarOferta(
			long organizationId, long consultorioId, long servicioId, String sufijo) {

		String nombre = "Oferta " + sufijo;
		jdbc().update("""
				INSERT INTO oferta_servicio_consultorio
				    (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				     duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				     genera_registro_clinico, requiere_profesional, requiere_espacio,
				     vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'GRUPAL', 45, 8, 0, 1, 1, 1, 1, CURRENT_DATE(), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, servicioId, nombre);
		return jdbc().queryForObject("""
				SELECT id FROM oferta_servicio_consultorio
				 WHERE organization_id = ? AND nombre_comercial = ?
				""", Long.class, organizationId, nombre);
	}

	private long insertarCuenta(String sufijo) {
		String email = "habilitacion." + sufijo + "@ejemplo.test";
		jdbc().update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado,
				                    active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetica', 'De Prueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		return jdbc().queryForObject("SELECT id FROM cuenta WHERE email = ?", Long.class, email);
	}

	private long insertarMembership(long organizationId, long accountId) {
		jdbc().update("""
				INSERT INTO membership (organization_id, account_id, role_code, estado,
				                        valid_from, active, version, created_at, updated_at)
				VALUES (?, ?, 'PROFESIONAL', 'ACTIVA', UTC_TIMESTAMP(6), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, accountId);
		return jdbc().queryForObject("""
				SELECT id FROM membership WHERE organization_id = ? AND account_id = ?
				""", Long.class, organizationId, accountId);
	}

	private long insertarEspacio(long organizationId, long consultorioId, String sufijo) {
		String name = "Box " + sufijo;
		jdbc().update("""
				INSERT INTO espacio (organization_id, consultorio_id, name, tipo, capacidad,
				                     valid_from, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 'SALA_GRUPAL', 6, UTC_TIMESTAMP(6), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, name);
		return jdbc().queryForObject("""
				SELECT id FROM espacio WHERE organization_id = ? AND name = ?
				""", Long.class, organizationId, name);
	}
}
