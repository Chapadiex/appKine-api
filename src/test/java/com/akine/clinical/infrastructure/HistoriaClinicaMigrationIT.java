package com.akine.clinical.infrastructure;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
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
 * V32 contra MySQL real (AKINE-04.01): {@code historia_clinica} y
 * {@code historia_clinica_antecedente}.
 *
 * <p>Mismo criterio que {@code PersonaMigrationIT}: se inserta directo contra una base real,
 * porque un CHECK que la aplicacion respeta y la base no tiene deja de proteger en cuanto alguien
 * escribe por otro camino —una migracion de datos, un fix a mano—.
 *
 * <h2>Lo que este test prueba y los unitarios no pueden</h2>
 *
 * <ol>
 *   <li>Que <b>no puedan existir dos historias vigentes</b> del mismo paciente en una
 *       organizacion, y que si puedan en organizaciones distintas. Es "una HC por paciente y
 *       Organizacion" (DP-03) y es tambien lo que hace idempotente la apertura sin ventana de
 *       carrera: la garantia es del indice, no del servicio.</li>
 *   <li>Que la historia <b>no lleve consultorio_id</b> ni ninguna columna que copie datos
 *       administrativos del paciente (RN-M09-003) ni que apunte a un artefacto clinico de otra
 *       etapa (regla maestra 1).</li>
 *   <li>Que la baja de un antecedente <b>exija motivo</b> en la base, no solo en el dominio
 *       (RN-M09-004).</li>
 * </ol>
 *
 * <p>Ninguna se puede probar con dobles: son propiedades del esquema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class HistoriaClinicaMigrationIT {

	private static final long ORG_A = 9201L;
	private static final long ORG_B = 9202L;

	/** Cada test siembra sus propias personas: el unique de documento no perdona repeticiones. */
	private static final AtomicLong SECUENCIA = new AtomicLong(40100000L);

	@Autowired
	private DataSource dataSource;

	private JdbcTemplate jdbc;
	private long personaEnA;
	private long personaEnB;

	private JdbcTemplate jdbc() {
		if (jdbc == null) {
			jdbc = new JdbcTemplate(dataSource);
		}
		return jdbc;
	}

	@BeforeEach
	void sembrarPersonas() {
		// Las FK de organization son reales, asi que las organizaciones tienen que existir. Datos
		// sinteticos, como manda AGENT.md seccion 10.
		crearOrganizacion(ORG_A, "hc-org-a");
		crearOrganizacion(ORG_B, "hc-org-b");
		personaEnA = crearPersona(ORG_A);
		personaEnB = crearPersona(ORG_B);
	}

	// =================================================================================
	// Tenant y forma de la tabla
	// =================================================================================

	@Test
	@DisplayName("las dos tablas llevan organization_id NOT NULL: no hay historia global")
	void las_dos_tablas_llevan_tenant() {
		for (String tabla : List.of("historia_clinica", "historia_clinica_antecedente")) {
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
		// Si el tenant no va primero, el indice no sirve para acotar por organizacion. Se excluyen
		// los que MySQL crea sola para sostener cada FK: mismo filtro y mismo motivo que
		// PersonaMigrationIT.
		for (String tabla : List.of("historia_clinica", "historia_clinica_antecedente")) {
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

			assertThat(primeras)
					.as("%s tiene que conservar su unique y sus indices de busqueda", tabla)
					.isNotEmpty();
			assertThat(primeras)
					.as("todo indice declarado de %s tiene que empezar por organization_id", tabla)
					.containsOnly("organization_id");
		}
	}

	@Test
	@DisplayName("la historia es de la ORGANIZACION: no tiene consultorio_id")
	void la_historia_no_es_de_una_sede() {
		// DP-03 escrita en el esquema. Con consultorio_id, la misma persona tendria dos historias
		// en un centro con dos sedes y la longitudinalidad se perderia justo en el caso que la
		// motiva.
		assertThat(columnasDe("historia_clinica")).doesNotContain("consultorio_id");
	}

	@Test
	@DisplayName("la historia no duplica datos del paciente ni apunta a artefactos clinicos")
	void la_historia_no_duplica_ni_se_adelanta() {
		// RN-M09-003 y regla maestra 1. Si aparece aca una columna que copie el apellido o que
		// apunte a un Caso, esta tabla dejo de ser lo que 04.01 decidio que fuera.
		assertThat(columnasDe("historia_clinica"))
				.doesNotContain("apellido", "nombre", "numero_documento", "telefono",
						"caso_clinico_id", "sesion_id", "perfil_paciente_id");
	}

	// =================================================================================
	// El unique: una HC por paciente y organizacion
	// =================================================================================

	@Test
	@DisplayName("dos historias vigentes del mismo paciente no entran en la misma organizacion")
	void la_historia_es_unica_por_paciente_y_organizacion() {
		abrirHistoria(ORG_A, personaEnA);

		assertThatThrownBy(() -> abrirHistoria(ORG_A, personaEnA))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("la misma persona en otra organizacion tiene su propia historia")
	void cada_organizacion_tiene_su_propia_historia() {
		// La misma persona real puede ser paciente de dos centros y cada uno tiene su historia.
		// Nunca se comparte entre organizaciones (DP-03), y esa separacion la sostiene el unique.
		abrirHistoria(ORG_A, personaEnA);

		assertThatCode(() -> abrirHistoria(ORG_B, personaEnB)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("una historia dada de baja libera el lugar para una nueva")
	void una_baja_libera_el_lugar() {
		// Es lo que hace `deleted_key`. Con un unique sobre deleted_at a secas, todas las vigentes
		// tendrian NULL y no colisionarian entre si: el unique protegeria el historico y
		// desprotegeria lo vigente.
		abrirHistoria(ORG_A, personaEnA);
		jdbc().update("""
				UPDATE historia_clinica
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6),
				       deactivation_reason = 'ficha fusionada'
				 WHERE organization_id = ? AND persona_id = ?
				""", ORG_A, personaEnA);

		assertThatCode(() -> abrirHistoria(ORG_A, personaEnA)).doesNotThrowAnyException();
	}

	// =================================================================================
	// Los CHECK
	// =================================================================================

	@Test
	@DisplayName("un resumen sin autor ni fecha no entra: RN-M09-004 lo exige trazable")
	void el_resumen_viaja_con_su_autoria() {
		long historiaId = abrirHistoria(ORG_A, personaEnA);

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE historia_clinica SET resumen = 'dolor lumbar' WHERE id = ?
				""", historiaId))
				.as("tiene que violar ck_historia_clinica_resumen_trazable. El CHECK de MySQL llega "
						+ "como UncategorizedSQLException, al reves que el UNIQUE")
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una baja de antecedente sin motivo no entra")
	void la_baja_de_antecedente_exige_motivo() {
		long historiaId = abrirHistoria(ORG_A, personaEnA);
		long antecedenteId = registrarAntecedente(ORG_A, historiaId, "ALERGIA");

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE historia_clinica_antecedente
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6)
				 WHERE id = ?
				""", antecedenteId))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un tipo de antecedente fuera del catalogo no entra")
	void el_tipo_de_antecedente_es_lista_cerrada() {
		long historiaId = abrirHistoria(ORG_A, personaEnA);

		assertThatThrownBy(() -> registrarAntecedente(ORG_A, historiaId, "VACUNA"))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("varios antecedentes con la misma descripcion conviven: no hay unique que lo impida")
	void los_antecedentes_no_son_unicos() {
		// Dos alergias con texto parecido son un caso clinico legitimo. Un unique ahi convertiria
		// un dato clinico en un conflicto administrativo.
		long historiaId = abrirHistoria(ORG_A, personaEnA);
		registrarAntecedente(ORG_A, historiaId, "ALERGIA");

		assertThatCode(() -> registrarAntecedente(ORG_A, historiaId, "ALERGIA"))
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// Fixtures — todo sintetico
	// =================================================================================

	private List<String> columnasDe(String tabla) {
		return jdbc().queryForList("""
				SELECT column_name FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name = ?
				""", String.class, tabla);
	}

	private void crearOrganizacion(long id, String slug) {
		jdbc().update("""
				INSERT IGNORE INTO organization (id, name, slug, created_at, updated_at)
				 VALUES (?, ?, ?, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", id, "Centro " + slug, slug);
	}

	private long crearPersona(long organizationId) {
		String documento = String.valueOf(SECUENCIA.incrementAndGet());
		jdbc().update("""
				INSERT INTO persona (organization_id, tipo_documento, numero_documento,
				                     documento_clave, apellido, nombre, apellido_clave,
				                     nombre_clave, created_at, updated_at)
				 VALUES (?, 'DNI', ?, ?, 'Perez', 'Ana', 'PEREZ', 'ANA',
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, documento, documento);
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long abrirHistoria(long organizationId, long personaId) {
		jdbc().update("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              created_at, updated_at)
				 VALUES (?, ?, UTC_TIMESTAMP(6), 1, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId);
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long registrarAntecedente(long organizationId, long historiaId, String tipo) {
		jdbc().update("""
				INSERT INTO historia_clinica_antecedente (organization_id, historia_clinica_id, tipo,
				                                          descripcion, registrado_en, registrado_por,
				                                          created_at, updated_at)
				 VALUES (?, ?, ?, 'penicilina', UTC_TIMESTAMP(6), 1,
				         UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, historiaId, tipo);
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
