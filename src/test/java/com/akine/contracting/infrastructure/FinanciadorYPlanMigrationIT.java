package com.akine.contracting.infrastructure;

import com.akine.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V41 contra MySQL real (AKINE-03.03): {@code financiador} y {@code plan_cobertura}.
 *
 * <p>Verifica que las garantias que el diseno discutio existan <b>en la base</b> y no solo en el
 * documento. Mismo criterio que {@code PersonaMigrationIT} y {@code ServicioYOfertaMigrationIT}:
 * se inserta directo contra una base real, porque un CHECK que la aplicacion respeta y la base no
 * tiene deja de proteger en cuanto alguien escribe por otro camino —una migracion de datos, un
 * fix a mano—.
 *
 * <h2>Lo que este test prueba y los unitarios no pueden</h2>
 *
 * <ol>
 *   <li>Que dos financiadores vigentes con el mismo codigo <b>no puedan existir</b> en una
 *       organizacion, y que si puedan en organizaciones distintas.</li>
 *   <li>Que un codigo liberado por una baja logica <b>se pueda reusar</b>, que es lo que hace el
 *       centinela {@code deleted_key} y que un unique sobre {@code deleted_at} a secas romperia.</li>
 *   <li>Que <b>varios financiadores SIN CUIT convivan sin chocar</b>. Es el comportamiento de
 *       MySQL con los NULL en un unique, y es exactamente el que hace falta en el mostrador.</li>
 *   <li>Que el codigo de un plan sea unico <b>dentro de su financiador</b> y no de la
 *       organizacion: dos financiadores pueden tener los dos un plan "210".</li>
 *   <li>Que los CHECK de coherencia —baja, vigencia, copago con moneda— rechacen desde la base.</li>
 * </ol>
 *
 * <p>Ninguna se puede probar con dobles: son propiedades del esquema.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class FinanciadorYPlanMigrationIT {

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
	@DisplayName("las dos tablas llevan organization_id NOT NULL: no hay financiador global")
	void las_dos_tablas_llevan_tenant() {
		// A diferencia de `servicio`, aca NO hay excepcion de ADR-0023 que declarar. El catalogo
		// global de financiadores que menciona la matriz §3 no existe en 03.03: ver V41 y la
		// matriz §13.2.
		for (String tabla : List.of("financiador", "plan_cobertura")) {
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
	@DisplayName("todo unique e indice DECLARADO empieza por organization_id")
	void los_indices_empiezan_por_tenant() {
		// Un unique sin la columna de tenant es un bug de aislamiento aunque hoy sea deducible por
		// la FK (AGENT.md §5).
		//
		// Se miran solo los indices DECLARADOS —uk_ e ix_—. Los que MySQL crea solo para sostener
		// una FK (fk_plan_financiador) empiezan por la columna de la FK y no se pueden reordenar:
		// no son una decision de este esquema y exigirles el prefijo de tenant seria pedirle a la
		// base algo que la base no ofrece.
		for (String tabla : List.of("financiador", "plan_cobertura")) {
			List<String> primeras = jdbc().queryForList("""
					SELECT column_name FROM information_schema.statistics
					 WHERE table_schema = DATABASE() AND table_name = ?
					   AND seq_in_index = 1
					   AND (index_name LIKE 'uk\\_%' OR index_name LIKE 'ix\\_%')
					""", String.class, tabla);

			assertThat(primeras)
					.as("todo indice declarado de %s tiene que empezar por organization_id", tabla)
					.isNotEmpty()
					.containsOnly("organization_id");
		}
	}

	@Test
	@DisplayName("el mismo codigo de financiador convive en organizaciones distintas")
	void el_codigo_es_unico_por_organizacion() {
		long orgA = insertarOrganizacion();
		long orgB = insertarOrganizacion();

		insertarFinanciador(orgA, "OSDE", "OSDE en A", null);

		assertThatCode(() -> insertarFinanciador(orgB, "OSDE", "OSDE en B", null))
				.as("dos centros distintos trabajan con la misma obra social")
				.doesNotThrowAnyException();

		assertThatThrownBy(() -> insertarFinanciador(orgA, "OSDE", "OSDE otra vez", null))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// =================================================================================
	// El centinela deleted_key
	// =================================================================================

	@Test
	@DisplayName("un codigo liberado por una baja logica se puede reusar")
	void el_codigo_se_reusa_despues_de_la_baja() {
		// Es lo que hace el centinela deleted_key. Un unique sobre deleted_at a secas protegeria
		// el historico y desprotegeria lo vigente, porque en MySQL varios NULL no colisionan.
		long org = insertarOrganizacion();
		long id = insertarFinanciador(org, "SWISS", "Swiss Medical", null);

		jdbc().update("""
				UPDATE financiador
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6), deactivation_reason = 'baja'
				 WHERE id = ?
				""", id);

		assertThatCode(() -> insertarFinanciador(org, "SWISS", "Swiss Medical de nuevo", null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("varios financiadores SIN CUIT conviven sin chocar")
	void los_nulos_no_colisionan() {
		// El comportamiento de MySQL con los NULL en un unique, aprovechado a proposito: en el
		// mostrador se carga una obra social sin tener su CUIT a mano.
		long org = insertarOrganizacion();

		insertarFinanciador(org, "A", "Sin CUIT uno", null);

		assertThatCode(() -> insertarFinanciador(org, "B", "Sin CUIT dos", null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el mismo CUIT vigente dos veces en la organizacion se rechaza")
	void el_cuit_es_unico_entre_vigentes() {
		long org = insertarOrganizacion();
		insertarFinanciador(org, "A", "Uno", "30712345678");

		assertThatThrownBy(() -> insertarFinanciador(org, "B", "Dos", "30712345678"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("un CUIT sin normalizar lo rechaza la BASE, no solo la aplicacion")
	void el_cuit_se_guarda_normalizado() {
		// La normalizacion la hace la aplicacion; este CHECK impide que otro camino de escritura
		// —una carga a mano, una migracion— meta un formato distinto y rompa el unique sin que
		// nadie se entere.
		long org = insertarOrganizacion();

		assertThatThrownBy(() -> insertarFinanciador(org, "A", "Uno", "30-71234567-8"))
				.isInstanceOfAny(DataIntegrityViolationException.class,
						UncategorizedSQLException.class);
	}

	// =================================================================================
	// Planes
	// =================================================================================

	@Test
	@DisplayName("el codigo del plan es unico dentro del FINANCIADOR, no de la organizacion")
	void el_codigo_del_plan_es_por_financiador() {
		// Obligar a que no se repita entre financiadores forzaria a inventar codigos que el
		// financiador real no usa.
		long org = insertarOrganizacion();
		long unFinanciador = insertarFinanciador(org, "A", "Uno", null);
		long otroFinanciador = insertarFinanciador(org, "B", "Dos", null);

		insertarPlan(org, unFinanciador, "210", "Plan 210", "2026-01-01", null);

		assertThatCode(() -> insertarPlan(org, otroFinanciador, "210", "Plan 210", "2026-01-01", null))
				.doesNotThrowAnyException();

		assertThatThrownBy(
				() -> insertarPlan(org, unFinanciador, "210", "Plan 210 bis", "2026-01-01", null))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@DisplayName("un plan que vale un solo dia es valido; una vigencia invertida no")
	void la_vigencia_es_inclusiva() {
		// vigencia_hasta es el ULTIMO dia INCLUSIVE, asi que la igualdad se admite. Es donde V41
		// se aparta de la cabecera de V24, que llamaba "EXCLUSIVA" a la suya mientras su codigo
		// Java la evaluaba inclusiva.
		long org = insertarOrganizacion();
		long financiador = insertarFinanciador(org, "A", "Uno", null);

		assertThatCode(
				() -> insertarPlan(org, financiador, "1D", "Un dia", "2026-01-01", "2026-01-01"))
				.doesNotThrowAnyException();

		assertThatThrownBy(
				() -> insertarPlan(org, financiador, "INV", "Invertido", "2026-02-01", "2026-01-01"))
				.isInstanceOfAny(DataIntegrityViolationException.class,
						UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un copago sin moneda lo rechaza la base")
	void el_copago_viaja_con_moneda() {
		long org = insertarOrganizacion();
		long financiador = insertarFinanciador(org, "A", "Uno", null);

		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, copago, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, 'X', 'X', '2026-01-01', 0, 1, 100.00, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, financiador))
				.isInstanceOfAny(DataIntegrityViolationException.class,
						UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una baja incoherente —inactivo sin fecha ni motivo— la rechaza la base")
	void la_baja_es_coherente() {
		// Sin este CHECK es posible active = 0 con deleted_at NULL, que ademas rompe el centinela
		// del unique porque la fila cae en 1970 junto con las activas.
		long org = insertarOrganizacion();
		long id = insertarFinanciador(org, "A", "Uno", null);

		assertThatThrownBy(() -> jdbc().update("UPDATE financiador SET active = 0 WHERE id = ?", id))
				.isInstanceOfAny(DataIntegrityViolationException.class,
						UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("no se puede borrar fisicamente un financiador con planes: la FK es RESTRICT")
	void la_fk_impide_el_borrado_fisico() {
		// RN-M15-003 prohibe eliminar historicos, y la base lo hace cumplir sin depender de que la
		// aplicacion se acuerde.
		long org = insertarOrganizacion();
		long financiador = insertarFinanciador(org, "A", "Uno", null);
		insertarPlan(org, financiador, "210", "Plan 210", "2026-01-01", null);

		assertThatThrownBy(() -> jdbc().update("DELETE FROM financiador WHERE id = ?", financiador))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// =================================================================================
	// Apoyo — datos sintéticos únicamente
	// =================================================================================

	private long insertarOrganizacion() {
		String slug = "contracting-" + UUID.randomUUID().toString().substring(0, 12);
		jdbc().update("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Financiadores Sinteticos " + slug, slug);
		return jdbc().queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, slug);
	}

	private long insertarFinanciador(long organizationId, String codigo, String nombre, String cuit) {
		jdbc().update("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, cuit, active,
				                         version, created_at, updated_at)
				VALUES (?, ?, ?, 'PREPAGA', ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, codigo, nombre, cuit);
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarPlan(
			long organizationId, long financiadorId, String codigo, String nombre,
			String desde, String hasta) {

		jdbc().update("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, vigencia_hasta, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 0, 1, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, financiadorId, codigo, nombre, desde, hasta);
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
