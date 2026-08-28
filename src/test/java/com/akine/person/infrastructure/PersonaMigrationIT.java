package com.akine.person.infrastructure;

import java.util.List;

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
 * V27 contra MySQL real (AKINE-03.01): {@code persona} y {@code perfil_paciente}.
 *
 * <p>Verifica que las garantias que el diseno discutio existan <b>en la base</b> y no solo en el
 * documento. Mismo criterio que {@code ServicioYOfertaMigrationIT} y
 * {@code HabilitacionesMigrationIT}: se inserta directo contra una base real, porque un CHECK que
 * la aplicacion respeta y la base no tiene deja de proteger en cuanto alguien escribe por otro
 * camino —una migracion de datos, un fix a mano—.
 *
 * <h2>Lo que este test prueba y los unitarios no pueden</h2>
 *
 * <p>Las tres afirmaciones centrales del diseno viven todas en el esquema:
 *
 * <ol>
 *   <li>Que dos personas vigentes con el mismo documento <b>no puedan existir</b> en una
 *       organizacion, y que si puedan en organizaciones distintas.</li>
 *   <li>Que <b>varias personas SIN documento convivan sin chocar</b>. Es el comportamiento de
 *       MySQL con los NULL en un unique, y es exactamente el que la etapa necesita: sin el habria
 *       que inventar documentos falsos en el mostrador.</li>
 *   <li>Que un documento liberado por una baja logica <b>se pueda reusar</b>, que es lo que hace
 *       el centinela {@code deleted_key} y que un unique sobre {@code deleted_at} a secas
 *       romperia.</li>
 * </ol>
 *
 * <p>Ninguna de las tres se puede probar con dobles: son propiedades del indice.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PersonaMigrationIT {

	private static final long ORG_A = 9101L;
	private static final long ORG_B = 9102L;

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
	@DisplayName("las dos tablas llevan organization_id NOT NULL: no hay persona global")
	void las_dos_tablas_llevan_tenant() {
		// A diferencia de `servicio`, aca NO hay excepcion de ADR-0023 que declarar. Preguntar de
		// que organizacion es una persona SIEMPRE tiene respuesta.
		for (String tabla : List.of("persona", "perfil_paciente")) {
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
	@DisplayName("todo unique e indice declarado empieza por organization_id")
	void los_indices_empiezan_por_el_tenant() {
		// Si el tenant no va primero, el indice no sirve para acotar por organizacion y la
		// busqueda del mostrador termina escaneando el padron de otros centros para descartarlo.
		// Se excluyen los indices que MySQL crea sola para sostener cada FK: mismo filtro y mismo
		// motivo que HabilitacionesMigrationIT.
		for (String tabla : List.of("persona", "perfil_paciente")) {
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
					.as("%s tiene que conservar su unique y sus indices de busqueda", tabla)
					.isNotEmpty();
			assertThat(primeras)
					.as("todo indice declarado de %s tiene que empezar por organization_id", tabla)
					.containsOnly("organization_id");
		}
	}

	// =================================================================================
	// El unique de documento: las tres propiedades que sostienen el diseno
	// =================================================================================

	@Test
	@DisplayName("dos personas vigentes con el mismo documento no entran en la misma organizacion")
	void el_documento_es_unico_dentro_de_la_organizacion() {
		insertarPersona(ORG_A, "DNI", "30111222", "Perez", "Ana");

		assertThatThrownBy(() -> insertarPersona(ORG_A, "DNI", "30111222", "Gomez", "Otra"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("el mismo documento SI entra en otra organizacion: el unique lleva el tenant")
	void el_mismo_documento_entra_en_otro_tenant() {
		// La misma persona real puede ser paciente de dos centros distintos, y cada uno tiene su
		// propia ficha. Sin organization_id en el unique, el segundo centro no podria darla de alta.
		insertarPersona(ORG_A, "DNI", "30333444", "Perez", "Ana");

		assertThatCode(() -> insertarPersona(ORG_B, "DNI", "30333444", "Perez", "Ana"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("varias personas SIN documento conviven: los NULL no colisionan, y es lo buscado")
	void varias_personas_sin_documento_conviven() {
		// Es la propiedad de la que depende el caso borde "persona sin DNI" de la etapa. Si los
		// NULL colisionaran, el mostrador tendria que inventar documentos falsos para el segundo
		// menor sin DNI del dia — que es la peor salida posible: contamina el padron con claves
		// que despues chocan de verdad.
		insertarPersona(ORG_A, null, null, "Sin", "Documento Uno");

		assertThatCode(() -> insertarPersona(ORG_A, null, null, "Sin", "Documento Dos"))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el documento de una persona dada de baja se puede reusar")
	void una_baja_libera_el_documento() {
		// Es lo que hace `deleted_key`. Con un unique sobre deleted_at a secas, todas las filas
		// vigentes tendrian NULL, no colisionarian entre si —o sea el unique protegeria el
		// historico y desprotegeria lo vigente— y ademas nunca se podria reusar el documento.
		insertarPersona(ORG_A, "DNI", "30555666", "Perez", "Ana");
		jdbc().update("""
				UPDATE persona
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6),
				       deactivation_reason = 'ficha duplicada'
				 WHERE organization_id = ? AND documento_clave = ?
				""", ORG_A, "30555666");

		assertThatCode(() -> insertarPersona(ORG_A, "DNI", "30555666", "Perez", "Ana"))
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// Los CHECK
	// =================================================================================

	@Test
	@DisplayName("un tipo de documento sin numero no entra: el par es indivisible")
	void el_documento_incompleto_no_entra() {
		// El dominio tambien lo rechaza, y hace falta que ademas lo rechace la base: un tipo sin
		// numero no identifica a nadie, y una migracion de datos escribe sin pasar por el dominio.
		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO persona (organization_id, tipo_documento, numero_documento,
				                     documento_clave, apellido, nombre, apellido_clave,
				                     nombre_clave, created_at, updated_at)
				 VALUES (?, 'DNI', NULL, NULL, 'Perez', 'Ana', 'PEREZ', 'ANA',
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", ORG_A))
				.as("tiene que violar ck_persona_documento_completo. El CHECK de MySQL llega como "
						+ "UncategorizedSQLException y no como violacion de integridad, al reves "
						+ "que el UNIQUE")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una baja sin motivo no entra: la auditoria no puede quedar sin poder explicarla")
	void la_baja_exige_motivo() {
		insertarPersona(ORG_A, "DNI", "30777888", "Perez", "Ana");

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE persona
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6)
				 WHERE organization_id = ? AND documento_clave = ?
				""", ORG_A, "30777888"))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un tipo de documento fuera del catalogo no entra")
	void el_tipo_de_documento_es_lista_cerrada() {
		assertThatThrownBy(() -> insertarPersona(ORG_A, "CUIL", "20301112223", "Perez", "Ana"))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	// =================================================================================
	// perfil_paciente
	// =================================================================================

	@Test
	@DisplayName("una persona no puede tener dos perfiles de paciente vigentes")
	void el_perfil_es_unico_por_persona() {
		// Es lo que hace que la activacion sea idempotente SIN ventana de carrera: dos requests
		// simultaneos no producen dos perfiles, el segundo choca aca. Un pre-chequeo en la
		// aplicacion no da esa garantia.
		long personaId = insertarPersona(ORG_A, "DNI", "30999000", "Perez", "Ana");

		activarPerfil(personaId);

		assertThatThrownBy(() -> activarPerfil(personaId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("un perfil dado de baja se puede volver a activar")
	void un_perfil_cerrado_se_puede_reactivar() {
		// Caso borde declarado de la etapa: "perfil dado de baja". Una persona vigente cuyo perfil
		// se cerro y que vuelve a iniciar atencion es un alta legitima, no un conflicto.
		long personaId = insertarPersona(ORG_A, "DNI", "31000111", "Perez", "Ana");
		activarPerfil(personaId);
		jdbc().update("""
				UPDATE perfil_paciente
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6),
				       deactivation_reason = 'alta clinica'
				 WHERE persona_id = ?
				""", personaId);

		assertThatCode(() -> activarPerfil(personaId)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("perfil_paciente no referencia ningun artefacto clinico")
	void el_perfil_no_toca_lo_clinico() {
		// RF-M07-010 y regla maestra 1: que exista el perfil significa "esta persona es paciente",
		// NO que tenga Historia Clinica. Si algun dia aparece aca una columna que apunte a M09, la
		// activacion pasaria a crear por transitividad el artefacto que la etapa prohibe crear.
		List<String> columnas = jdbc().queryForList("""
				SELECT column_name FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name = 'perfil_paciente'
				""", String.class);

		assertThat(columnas)
				.doesNotContain("historia_clinica_id", "caso_clinico_id", "sesion_id");
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	/** Inserta directo, sin pasar por el dominio: es el punto del test. Devuelve el id. */
	private long insertarPersona(
			Long organizationId, String tipo, String numero, String apellido, String nombre) {

		jdbc().update("""
				INSERT INTO persona (organization_id, tipo_documento, numero_documento,
				                     documento_clave, apellido, nombre, apellido_clave,
				                     nombre_clave, created_at, updated_at)
				 VALUES (?, ?, ?, ?, ?, ?, UPPER(?), UPPER(?), UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, tipo, numero, numero, apellido, nombre, apellido, nombre);

		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void activarPerfil(long personaId) {
		jdbc().update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             created_at, updated_at)
				 VALUES (?, ?, UTC_TIMESTAMP(6), 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", ORG_A, personaId);
	}
}
