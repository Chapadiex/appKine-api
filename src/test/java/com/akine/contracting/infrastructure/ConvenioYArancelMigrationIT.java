package com.akine.contracting.infrastructure;

import com.akine.TestcontainersConfiguration;
import com.akine.contracting.ConvenioFixtures;
import com.akine.contracting.ConvenioFixtures.Escenario;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V43 contra MySQL real: {@code convenio}, {@code convenio_arancel} y {@code convenio_lock}.
 *
 * <p>Verifica que las garantias que el diseno discutio existan <b>en la base</b> y no solo en el
 * documento. Mismo criterio que {@code FinanciadorYPlanMigrationIT}: se inserta directo contra una
 * base real, porque un CHECK que la aplicacion respeta y la base no deja de proteger en cuanto
 * alguien escribe por otro camino —una migracion de datos, un fix a mano—.
 *
 * <p><b>Lo que este test declara por ausencia es tan importante como lo que afirma:</b> NO hay
 * ningun indice de no-solapamiento y no puede haberlo. Esa regla la prueba
 * {@code ConvenioConcurrenteIT}, contra el lock, y aca solo se comprueba que la fila-lock exista y
 * sea unica por sede.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ConvenioYArancelMigrationIT {

	@Autowired
	private JdbcTemplate jdbc;

	private ConvenioFixtures fixtures;

	@BeforeEach
	void prepararFixtures() {
		fixtures = new ConvenioFixtures(jdbc);
	}

	// =================================================================================
	// Tenant
	// =================================================================================

	@Test
	@DisplayName("las tres tablas llevan organization_id NOT NULL")
	void las_tablas_llevan_tenant() {
		for (String tabla : List.of("convenio", "convenio_arancel", "convenio_lock")) {
			assertThat(jdbc.queryForObject("""
					SELECT is_nullable FROM information_schema.columns
					 WHERE table_schema = DATABASE() AND table_name = ?
					   AND column_name = 'organization_id'
					""", String.class, tabla))
					.as("%s tiene que llevar organization_id NOT NULL", tabla)
					.isEqualTo("NO");
		}
	}

	@Test
	@DisplayName("todo unique e indice DECLARADO empieza por organization_id")
	void los_indices_empiezan_por_tenant() {
		// Un unique sin la columna de tenant es un bug de aislamiento aunque hoy sea deducible por
		// la FK (AGENT.md §5). Se miran solo los DECLARADOS —uk_ e ix_—: los que MySQL crea para
		// sostener una FK empiezan por la columna de la FK y no se pueden reordenar.
		for (String tabla : List.of("convenio", "convenio_arancel", "convenio_lock")) {
			assertThat(jdbc.queryForList("""
					SELECT column_name FROM information_schema.statistics
					 WHERE table_schema = DATABASE() AND table_name = ?
					   AND seq_in_index = 1
					   AND (index_name LIKE 'uk\\_%' OR index_name LIKE 'ix\\_%')
					""", String.class, tabla))
					.as("todo indice declarado de %s tiene que empezar por organization_id", tabla)
					.isNotEmpty()
					.containsOnly("organization_id");
		}
	}

	// =================================================================================
	// Lo que un unique SI puede expresar, y lo que no
	// =================================================================================

	@Test
	@DisplayName("el codigo de convenio es unico por SEDE, y otra sede puede reusarlo")
	void codigo_unico_por_sede() {
		Escenario unaSede = fixtures.crear();
		Escenario otraSede = fixtures.crear();

		insertarConvenio(unaSede, "OSDE-210", "2026-01-01", "2026-06-30");

		assertThatThrownBy(() -> insertarConvenio(unaSede, "OSDE-210", "2027-01-01", null))
				.as("la misma sede no puede repetirlo, aunque las vigencias no se pisen")
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThatCode(() -> insertarConvenio(otraSede, "OSDE-210", "2026-01-01", null))
				.as("otra sede si: cada una nombra su propio acuerdo")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("el codigo de un convenio dado de baja se puede reusar")
	void el_codigo_se_libera_con_la_baja() {
		// Es lo que hace el centinela deleted_key, y lo que un unique sobre deleted_at a secas
		// romperia: en MySQL varios NULL no colisionan, asi que protegeria el historico y
		// desprotegeria lo vigente.
		Escenario escenario = fixtures.crear();
		long id = insertarConvenio(escenario, "OSDE-210", "2026-01-01", null);

		jdbc.update("""
				UPDATE convenio SET active = 0, deleted_at = UTC_TIMESTAMP(6),
				                    deactivation_reason = 'Renegociado'
				 WHERE id = ?
				""", id);

		assertThatCode(() -> insertarConvenio(escenario, "OSDE-210", "2026-01-01", null))
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("NO existe ningun unique que impida dos convenios solapados, y no puede existir")
	void no_hay_unique_de_solapamiento() {
		// La base ACEPTA los dos: 01/01-30/06 y 01/03-31/12 no comparten ningun valor de columna, y
		// MySQL 8.4 no tiene exclusion constraints. Quien lo impide es ConvenioService bajo el lock
		// de convenio_lock, y eso lo prueba ConvenioConcurrenteIT.
		//
		// Este test existe para que quede escrito que la ausencia es deliberada: si alguien intenta
		// "arreglarlo" con un indice, va a descubrir aca que el indice no expresa la regla.
		Escenario escenario = fixtures.crear();

		insertarConvenio(escenario, "A", "2026-01-01", "2026-06-30");

		assertThatCode(() -> insertarConvenio(escenario, "B", "2026-03-01", "2026-12-31"))
				.as("la BASE los acepta: la regla no vive aca")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("tampoco hay unique de (convenio, practica): dos aranceles de la misma practica "
			+ "son el caso normal")
	void dos_aranceles_de_la_misma_practica_conviven() {
		Escenario escenario = fixtures.crear();
		long convenioId = insertarConvenio(escenario, "A", "2026-01-01", "2026-12-31");

		insertarArancel(escenario, convenioId, "12000.00", "2026-01-01", "2026-06-30");

		assertThatCode(() -> insertarArancel(
				escenario, convenioId, "18000.00", "2026-07-01", "2026-12-31"))
				.as("el de 2026 y el de 2027 conviven: lo prohibido es que se PISEN")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("la fila-lock es unica por sede: es lo que hace atomico el ON DUPLICATE KEY")
	void la_fila_lock_es_unica_por_sede() {
		Escenario escenario = fixtures.crear();

		jdbc.update("""
				INSERT INTO convenio_lock (organization_id, consultorio_id, created_at)
				VALUES (?, ?, UTC_TIMESTAMP(6))
				""", escenario.organizationId(), escenario.consultorioId());

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO convenio_lock (organization_id, consultorio_id, created_at)
				VALUES (?, ?, UTC_TIMESTAMP(6))
				""", escenario.organizationId(), escenario.consultorioId()))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// =================================================================================
	// CHECK de coherencia
	// =================================================================================

	@Test
	@DisplayName("una vigencia invertida se rechaza desde la BASE; una de un solo dia se admite")
	void vigencia_coherente() {
		Escenario escenario = fixtures.crear();

		assertThatThrownBy(() -> insertarConvenio(escenario, "X", "2026-06-30", "2026-01-01"))
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatCode(() -> insertarConvenio(escenario, "Y", "2026-06-30", "2026-06-30"))
				.as("un convenio que vale un solo dia es un estado real: hasta es INCLUSIVA")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("LA INVARIANTE ECONOMICA: las partes tienen que sumar el total, tambien en la base")
	void las_partes_suman_el_total() {
		// El CHECK existe porque la aplicacion no es el unico camino de escritura. Si algun dia se
		// admitiera un porcentaje de cobertura, ESTE es el constraint que habria que reemplazar por
		// una regla de redondeo documentada (§37) — y por eso conviene no hacerlo.
		Escenario escenario = fixtures.crear();
		long convenioId = insertarConvenio(escenario, "A", "2026-01-01", "2026-12-31");

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO convenio_arancel (organization_id, consultorio_id, convenio_id,
				                              practica_id, importe_total, importe_financiador,
				                              coseguro, moneda, vigencia_desde, active, version,
				                              created_at, updated_at)
				VALUES (?, ?, ?, ?, 12000.00, 9600.00, 2500.00, 'ARS', '2026-01-01', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", escenario.organizationId(), escenario.consultorioId(), convenioId,
				escenario.practicaId()))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un importe negativo se rechaza; cero es valido en las tres columnas")
	void importes_no_negativos() {
		Escenario escenario = fixtures.crear();
		long convenioId = insertarConvenio(escenario, "A", "2026-01-01", "2026-12-31");

		assertThatThrownBy(() -> insertarArancelCon(
				escenario, convenioId, "-1.00", "-1.00", "0.00", "2026-01-01"))
				.isInstanceOf(UncategorizedSQLException.class);

		assertThatCode(() -> insertarArancelCon(
				escenario, convenioId, "0.00", "0.00", "0.00", "2026-01-01"))
				.as("una practica sin cargo es un estado real")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("una baja incoherente —inactivo sin motivo— se rechaza desde la base")
	void baja_coherente() {
		Escenario escenario = fixtures.crear();
		long id = insertarConvenio(escenario, "A", "2026-01-01", null);

		assertThatThrownBy(() -> jdbc.update("UPDATE convenio SET active = 0 WHERE id = ?", id))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una modalidad fuera de la lista cerrada se rechaza")
	void modalidad_de_lista_cerrada() {
		Escenario escenario = fixtures.crear();

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO convenio (organization_id, consultorio_id, financiador_id, plan_id,
				                      codigo, nombre, modalidad, vigencia_desde, moneda,
				                      requiere_orden, requiere_autorizacion, requiere_credencial,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'Z', 'Z', 'INVENTADA', '2026-01-01', 'ARS', 0, 0, 1, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", escenario.organizationId(), escenario.consultorioId(),
				escenario.financiadorId(), escenario.planId()))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un tope mensual de cero se rechaza: sin tope se expresa con NULL")
	void tope_mensual_positivo() {
		Escenario escenario = fixtures.crear();

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO convenio (organization_id, consultorio_id, financiador_id, plan_id,
				                      codigo, nombre, modalidad, vigencia_desde, moneda,
				                      limite_sesiones_mensual, requiere_orden,
				                      requiere_autorizacion, requiere_credencial,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'W', 'W', 'POR_SESION', '2026-01-01', 'ARS', 0, 0, 0, 1, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", escenario.organizationId(), escenario.consultorioId(),
				escenario.financiadorId(), escenario.planId()))
				.isInstanceOf(UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("no se puede borrar fisicamente un convenio con aranceles: la FK es RESTRICT")
	void sin_borrado_fisico() {
		// §38 y RN-M16-003: la informacion historica no se elimina. La baja es logica y la FK lo
		// hace cumplir tambien contra un DELETE hecho a mano.
		Escenario escenario = fixtures.crear();
		long convenioId = insertarConvenio(escenario, "A", "2026-01-01", "2026-12-31");
		insertarArancel(escenario, convenioId, "1.00", "2026-01-01", "2026-12-31");

		assertThatThrownBy(() -> jdbc.update("DELETE FROM convenio WHERE id = ?", convenioId))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// =================================================================================
	// Inserciones
	// =================================================================================

	private long insertarConvenio(
			Escenario escenario, String codigo, String desde, String hasta) {

		jdbc.update("""
				INSERT INTO convenio (organization_id, consultorio_id, financiador_id, plan_id,
				                      codigo, nombre, modalidad, vigencia_desde, vigencia_hasta,
				                      moneda, requiere_orden, requiere_autorizacion,
				                      requiere_credencial, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'POR_PRESTACION', ?, ?, 'ARS', 0, 0, 1, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", escenario.organizationId(), escenario.consultorioId(),
				escenario.financiadorId(), escenario.planId(), codigo, "Convenio " + codigo,
				desde, hasta);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private void insertarArancel(
			Escenario escenario, long convenioId, String importe, String desde, String hasta) {

		jdbc.update("""
				INSERT INTO convenio_arancel (organization_id, consultorio_id, convenio_id,
				                              practica_id, importe_total, importe_financiador,
				                              coseguro, moneda, vigencia_desde, vigencia_hasta,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 0.00, 'ARS', ?, ?, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", escenario.organizationId(), escenario.consultorioId(), convenioId,
				escenario.practicaId(), importe, importe, desde, hasta);
	}

	private void insertarArancelCon(
			Escenario escenario, long convenioId,
			String total, String financiador, String coseguro, String desde) {

		jdbc.update("""
				INSERT INTO convenio_arancel (organization_id, consultorio_id, convenio_id,
				                              practica_id, importe_total, importe_financiador,
				                              coseguro, moneda, vigencia_desde, active, version,
				                              created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, 'ARS', ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", escenario.organizationId(), escenario.consultorioId(), convenioId,
				escenario.practicaId(), total, financiador, coseguro, desde);
	}
}
