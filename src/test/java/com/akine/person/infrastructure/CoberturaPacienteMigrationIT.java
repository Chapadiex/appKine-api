package com.akine.person.infrastructure;

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
 * V42 contra MySQL real (AKINE-03.04): {@code cobertura_paciente} y {@code cobertura_persona_lock}.
 *
 * <h2>Lo que este test prueba y los unitarios no pueden</h2>
 *
 * <ol>
 *   <li>Que la <b>copia congelada viaje entera o no viaje</b>. Media referencia —un {@code plan_id}
 *       sin el nombre de aquel momento— es exactamente el puntero que la tabla existe para no ser,
 *       y sin el CHECK cualquier otro camino de escritura la podria crear.</li>
 *   <li>Que una cobertura PARTICULAR no admita credencial ni plan.</li>
 *   <li>Que el mismo numero de afiliado no entre dos veces vigente bajo el mismo plan, y que
 *       <b>si</b> entre despues de una baja logica: el centinela {@code deleted_key}.</li>
 *   <li>Que el borrado FISICO de un plan referenciado por una cobertura sea imposible (RESTRICT).
 *       Es la mitad estructural de "los historicos no se eliminan".</li>
 *   <li>Que el unique del candado exista: es lo que hace idempotente al
 *       {@code INSERT ... ON DUPLICATE KEY UPDATE} y, por lo tanto, lo que evita el deadlock.</li>
 * </ol>
 *
 * <p><b>Lo que NO prueba, y conviene saberlo:</b> el no-solapamiento de vigencias y la unicidad de
 * la cobertura principal <b>no estan en el esquema</b>, a proposito — ningun unique puede
 * expresarlos en MySQL 8.4—. Los hace cumplir el lock desde la aplicacion, y eso solo lo probaria
 * un test de concurrencia con dos hilos, que esta etapa no escribio.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CoberturaPacienteMigrationIT {

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
	@DisplayName("las dos tablas llevan organization_id NOT NULL y todo indice empieza por el")
	void el_tenant_esta_en_el_esquema() {
		for (String tabla : List.of("cobertura_paciente", "cobertura_persona_lock")) {
			String nullable = jdbc().queryForObject("""
					SELECT is_nullable FROM information_schema.columns
					 WHERE table_schema = DATABASE() AND table_name = ?
					   AND column_name = 'organization_id'
					""", String.class, tabla);

			assertThat(nullable)
					.as("%s tiene que llevar organization_id NOT NULL", tabla)
					.isEqualTo("NO");

			// Solo los indices DECLARADOS: los que MySQL crea para sostener una FK empiezan por la
			// columna de la FK y no son una decision de este esquema.
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

	// =================================================================================
	// La copia congelada
	// =================================================================================

	@Test
	@DisplayName("una cobertura FINANCIADA sin la copia completa del plan la rechaza la BASE")
	void la_referencia_congelada_viaja_entera() {
		// Media referencia es el puntero que esta tabla existe para no ser. La aplicacion lo
		// garantiza porque recibe el record entero; el CHECK lo garantiza contra los demas
		// caminos de escritura: una carga a mano, una migracion de datos.
		Contexto contexto = contexto();

		assertThatThrownBy(() -> jdbc().update("""
				INSERT INTO cobertura_paciente (organization_id, persona_id, tipo, plan_id,
				                                financiador_id, vigencia_desde, created_at, updated_at)
				VALUES (?, ?, 'FINANCIADA', ?, ?, '2026-09-01', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", contexto.org, contexto.persona, contexto.plan, contexto.financiador))
				.isInstanceOfAny(DataIntegrityViolationException.class,
						UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una cobertura PARTICULAR con plan o con credencial la rechaza la BASE")
	void particular_es_la_ausencia_de_plan() {
		// RN-M08-001 y RN-M15-004: Particular es la ausencia de plan financiado. Una PARTICULAR
		// con numero de afiliado seria una credencial huerfana, sin financiador ante quien valer.
		Contexto contexto = contexto();

		assertThatThrownBy(() -> insertar(contexto, "PARTICULAR", contexto.plan, "62000123456",
				"2026-09-01", null))
				.isInstanceOfAny(DataIntegrityViolationException.class,
						UncategorizedSQLException.class);
	}

	// =================================================================================
	// El unique del afiliado y el centinela deleted_key
	// =================================================================================

	@Test
	@DisplayName("el mismo afiliado no entra dos veces vigente bajo el mismo plan, y si tras la baja")
	void el_afiliado_es_unico_entre_vigentes() {
		Contexto contexto = contexto();
		long primera = insertar(contexto, "FINANCIADA", contexto.plan, "62000123456",
				"2026-01-01", null);

		assertThatThrownBy(() -> insertar(contexto, "FINANCIADA", contexto.plan, "62000123456",
				"2026-06-01", null))
				.isInstanceOf(DataIntegrityViolationException.class);

		jdbc().update("""
				UPDATE cobertura_paciente
				   SET active = 0, deleted_at = UTC_TIMESTAMP(6), deactivation_reason = 'error de carga'
				 WHERE id = ?
				""", primera);

		assertThatCode(() -> insertar(contexto, "FINANCIADA", contexto.plan, "62000123456",
				"2026-06-01", null))
				.as("el centinela deleted_key libera el afiliado tras la baja logica")
				.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("varias coberturas SIN afiliado conviven, y las PARTICULAR tambien")
	void los_nulos_no_colisionan() {
		// Es el comportamiento de MySQL con los NULL en un unique, aprovechado a proposito: sin el,
		// un paciente no podria tener dos periodos particulares.
		Contexto contexto = contexto();

		insertar(contexto, "PARTICULAR", null, null, "2026-01-01", "2026-03-31");

		assertThatCode(() -> insertar(contexto, "PARTICULAR", null, null, "2026-04-01", null))
				.doesNotThrowAnyException();
	}

	// =================================================================================
	// Coherencia y no eliminacion de historicos
	// =================================================================================

	@Test
	@DisplayName("la vigencia invertida se rechaza y la de un solo dia se admite")
	void la_vigencia_es_inclusiva() {
		Contexto contexto = contexto();

		assertThatCode(() -> insertar(contexto, "PARTICULAR", null, null,
				"2026-09-01", "2026-09-01"))
				.as("una cobertura de un solo dia es un estado real")
				.doesNotThrowAnyException();

		assertThatThrownBy(() -> insertar(contexto, "PARTICULAR", null, null,
				"2026-09-10", "2026-09-01"))
				.isInstanceOfAny(DataIntegrityViolationException.class,
						UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("una baja sin motivo la rechaza la BASE")
	void la_baja_exige_motivo() {
		Contexto contexto = contexto();
		long id = insertar(contexto, "PARTICULAR", null, null, "2026-09-01", null);

		assertThatThrownBy(() -> jdbc().update("""
				UPDATE cobertura_paciente SET active = 0, deleted_at = UTC_TIMESTAMP(6) WHERE id = ?
				""", id))
				.isInstanceOfAny(DataIntegrityViolationException.class,
						UncategorizedSQLException.class);
	}

	@Test
	@DisplayName("un plan referenciado por una cobertura no se puede borrar fisicamente")
	void los_historicos_no_se_eliminan() {
		// La FK es RESTRICT: es la mitad estructural de RN-M08-003. La otra mitad —que la baja
		// logica no toque la cobertura— la garantiza que ningun codigo las alcance.
		Contexto contexto = contexto();
		insertar(contexto, "FINANCIADA", contexto.plan, "62000123456", "2026-09-01", null);

		assertThatThrownBy(() ->
				jdbc().update("DELETE FROM plan_cobertura WHERE id = ?", contexto.plan))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	// =================================================================================
	// El candado
	// =================================================================================

	@Test
	@DisplayName("el candado tiene una sola fila por persona: es lo que evita el deadlock")
	void el_candado_es_idempotente() {
		// Sin este unique el INSERT ... ON DUPLICATE KEY UPDATE no seria idempotente, y la
		// creacion perezosa volveria a producir el deadlock que ya se pago tres veces.
		Contexto contexto = contexto();

		jdbc().update("""
				INSERT INTO cobertura_persona_lock (organization_id, persona_id, created_at)
				VALUES (?, ?, UTC_TIMESTAMP(6))
				ON DUPLICATE KEY UPDATE id = id
				""", contexto.org, contexto.persona);

		assertThatCode(() -> jdbc().update("""
				INSERT INTO cobertura_persona_lock (organization_id, persona_id, created_at)
				VALUES (?, ?, UTC_TIMESTAMP(6))
				ON DUPLICATE KEY UPDATE id = id
				""", contexto.org, contexto.persona))
				.as("la segunda vez no lanza: eso es todo el punto")
				.doesNotThrowAnyException();

		Long filas = jdbc().queryForObject("""
				SELECT COUNT(*) FROM cobertura_persona_lock
				 WHERE organization_id = ? AND persona_id = ?
				""", Long.class, contexto.org, contexto.persona);

		assertThat(filas).isEqualTo(1L);
	}

	// =================================================================================
	// Apoyo — todos los datos son sinteticos
	// =================================================================================

	private record Contexto(long org, long persona, long financiador, long plan) {
	}

	private Contexto contexto() {
		long org = insertarOrganizacion();
		long persona = insertarPersona(org);
		long financiador = insertarFinanciador(org);
		long plan = insertarPlan(org, financiador);
		return new Contexto(org, persona, financiador, plan);
	}

	private long insertar(
			Contexto contexto, String tipo, Long planId, String afiliado,
			String desde, String hasta) {

		boolean financiada = "FINANCIADA".equals(tipo);
		jdbc().update("""
				INSERT INTO cobertura_paciente (organization_id, persona_id, tipo,
				                                financiador_id, financiador_codigo,
				                                financiador_nombre, financiador_tipo,
				                                plan_id, plan_codigo, plan_nombre,
				                                requeria_autorizacion, requeria_credencial,
				                                referencia_capturada_el,
				                                numero_afiliado, vigencia_desde, vigencia_hasta,
				                                created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, UTC_TIMESTAMP(6),
				        UTC_TIMESTAMP(6))
				""",
				contexto.org, contexto.persona, tipo,
				financiada ? contexto.financiador : null,
				financiada ? "OSDE" : null,
				financiada ? "OSDE Binario" : null,
				financiada ? "PREPAGA" : null,
				planId,
				financiada ? "210" : null,
				financiada ? "Plan 210" : null,
				financiada ? 0 : null,
				financiada ? 1 : null,
				financiada ? "2026-09-01 00:00:00.000000" : null,
				afiliado, desde, hasta);

		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarOrganizacion() {
		String slug = "coberturas-" + UUID.randomUUID().toString().substring(0, 12);
		jdbc().update("""
				INSERT INTO organization (name, slug, timezone, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Coberturas Sinteticas " + slug, slug);
		return jdbc().queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class, slug);
	}

	private long insertarPersona(long organizationId) {
		String documento = String.valueOf(System.nanoTime()).substring(0, 8);
		jdbc().update("""
				INSERT INTO persona (organization_id, tipo_documento, numero_documento,
				                     documento_clave, apellido, nombre, apellido_clave,
				                     nombre_clave, created_at, updated_at)
				VALUES (?, 'DNI', ?, ?, 'Sintetica', 'Paciente', 'SINTETICA', 'PACIENTE',
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, documento, documento);
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarFinanciador(long organizationId) {
		jdbc().update("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, 'OSDE', 'OSDE Binario', 'PREPAGA', 1, 0, UTC_TIMESTAMP(6),
				        UTC_TIMESTAMP(6))
				""", organizationId);
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}

	private long insertarPlan(long organizationId, long financiadorId) {
		jdbc().update("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, '210', 'Plan 210', '2026-01-01', 0, 1, 1, 0, UTC_TIMESTAMP(6),
				        UTC_TIMESTAMP(6))
				""", organizationId, financiadorId);
		return jdbc().queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
