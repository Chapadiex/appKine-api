package com.akine.identity;

import com.akine.TestcontainersConfiguration;
import com.akine.identity.application.AccountActivationService;
import com.akine.identity.application.AuthenticationService;
import com.akine.identity.application.PlatformAdminBootstrapService;
import com.akine.identity.application.PlatformAdminBootstrapService.ResultadoBootstrap;
import com.akine.identity.domain.Cuenta;
import com.akine.identity.infrastructure.PlatformAdminBootstrapRunner;
import com.akine.identity.infrastructure.SecureLinkVault;
import com.akine.platform.spi.tenant.PlatformRoleDirectory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bootstrap del administrador de plataforma contra MySQL real (DP-14, AKINE-A-4).
 *
 * <p>El contexto arranca <b>con</b> {@code akine.bootstrap.admin-email}: el primer arranque es
 * el del propio contexto, sobre una base recien migrada donde la unica cuenta de plataforma es la
 * que sembro {@code V15}. El "segundo arranque" vuelve a ejecutar el mismo runner, que es lo que
 * haria un reinicio sobre esa base.
 *
 * <p>El worker del outbox va apagado: el enlace en claro vive en {@link SecureLinkVault} hasta
 * que el envio lo consume, y con el worker andando la carrera contra el envio haria que el paso
 * de activacion no encontrara el enlace. Es la misma credencial que viajaria en el correo; leerla
 * de ahi ejerce el flujo real de punta a punta sin atajos en la base.
 *
 * <p>Los metodos van en orden porque cuentan una sola historia: arranca, rearranca, se activa, y
 * despues ningun arranque vuelve a tocarla.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.NONE,
		properties = {
				"akine.bootstrap.admin-email=" + PlatformAdminBootstrapIT.EMAIL_OPERADOR,
				"akine.notification.worker.enabled=false"
		})
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PlatformAdminBootstrapIT {

	static final String EMAIL_OPERADOR = "Operaciones.Plataforma@Ejemplo.test";
	private static final String NORMALIZADO = "operaciones.plataforma@ejemplo.test";
	private static final String PASSWORD = "Sintetica-Akine-2026";

	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformAdminBootstrapRunner runner;
	@Autowired private PlatformAdminBootstrapService bootstrap;
	@Autowired private SecureLinkVault vault;
	@Autowired private AccountActivationService activacion;
	@Autowired private AuthenticationService autenticacion;
	@Autowired private PlatformRoleDirectory roles;

	@Test
	@Order(1)
	@DisplayName("primer arranque: la cuenta sembrada pasa al email del operador y queda una activacion en el outbox")
	void primer_arranque_rebautiza_y_encola() {
		Map<String, Object> cuenta = cuentaDePlataforma();
		assertThat(cuenta.get("email")).isEqualTo(EMAIL_OPERADOR);
		assertThat(cuenta.get("email_normalizado")).isEqualTo(NORMALIZADO);
		assertThat(cuenta.get("estado")).isEqualTo("PENDIENTE_ACTIVACION");
		assertThat(cuenta.get("password_hash")).isNull();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM cuenta WHERE email_normalizado = 'plataforma@akine.app'",
				Integer.class)).as("el email sembrado ya no existe").isZero();

		long tokenId = tokenDeActivacionVigente();
		Map<String, Object> outbox = jdbc.queryForMap(
				"SELECT tipo, destinatario, organization_id, referencia_token_id, payload_sanitizado "
						+ "FROM notification_outbox WHERE clave_idempotente = ?",
				"activacion:" + tokenId);
		assertThat(outbox.get("tipo")).isEqualTo("ACTIVACION_CUENTA");
		assertThat(outbox.get("destinatario")).isEqualTo(EMAIL_OPERADOR);
		assertThat(outbox.get("organization_id")).isNull();
		assertThat(outbox.get("referencia_token_id")).isEqualTo(String.valueOf(tokenId));
		assertThat(String.valueOf(outbox.get("payload_sanitizado"))).doesNotContain("token");

		Map<String, Object> auditoria = jdbc.queryForMap(
				"SELECT organization_id, actor_account_id, entity_id, previous_state, new_state, details "
						+ "FROM audit_event WHERE event_type = 'PLATFORM_ADMIN_BOOTSTRAP'");
		assertThat(auditoria.get("organization_id")).isNull();
		assertThat(auditoria.get("actor_account_id")).isNull();
		assertThat(((Number) auditoria.get("entity_id")).longValue()).isEqualTo(cuentaId());
		assertThat(auditoria.get("previous_state")).isEqualTo("ACTIVA");
		assertThat(auditoria.get("new_state")).isEqualTo("PENDIENTE_ACTIVACION");
		assertThat(String.valueOf(auditoria.get("details")))
				.contains("plataforma@akine.app", EMAIL_OPERADOR);
	}

	@Test
	@Order(2)
	@DisplayName("segundo arranque con el enlace vigente: no reenvia ni vuelve a auditar")
	void segundo_arranque_no_hace_nada() throws Exception {
		int outboxAntes = filas("notification_outbox");
		int tokensAntes = filas("token_verificacion");

		runner.run(null);

		assertThat(bootstrap.ejecutar(EMAIL_OPERADOR)).isEqualTo(ResultadoBootstrap.ENLACE_VIGENTE);
		assertThat(filas("notification_outbox")).isEqualTo(outboxAntes);
		assertThat(filas("token_verificacion")).isEqualTo(tokensAntes);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE event_type = 'PLATFORM_ADMIN_BOOTSTRAP'",
				Integer.class)).isEqualTo(1);
	}

	@Test
	@Order(3)
	@DisplayName("activar con el enlace deja al admin autenticandose y con el rol de plataforma vigente")
	void activar_con_el_enlace() {
		long tokenId = tokenDeActivacionVigente();
		String enlace = vault.leer(String.valueOf(tokenId), Instant.now()).orElseThrow();
		String tokenPlano = UriComponentsBuilder.fromUriString(enlace).build()
				.getQueryParams().getFirst("token");

		activacion.activar(tokenPlano, PASSWORD);

		Cuenta autenticada = autenticacion.autenticar(EMAIL_OPERADOR, PASSWORD);
		assertThat(autenticada.getId()).isEqualTo(cuentaId());
		assertThat(roles.isPlatformAdmin(autenticada.getId(), Instant.now())).isTrue();
		assertThat(cuentaDePlataforma().get("estado")).isEqualTo("ACTIVA");
	}

	@Test
	@Order(4)
	@DisplayName("con un administrador de plataforma con credencial, el bootstrap no hace nada")
	void con_admin_con_credencial_no_hace_nada() throws Exception {
		int outboxAntes = filas("notification_outbox");

		runner.run(null);
		assertThat(bootstrap.ejecutar("otra.casilla@ejemplo.test"))
				.isEqualTo(ResultadoBootstrap.YA_HAY_ADMIN_CON_CREDENCIAL);

		assertThat(cuentaDePlataforma().get("email")).isEqualTo(EMAIL_OPERADOR);
		assertThat(cuentaDePlataforma().get("estado")).isEqualTo("ACTIVA");
		assertThat(filas("notification_outbox")).isEqualTo(outboxAntes);
	}

	// =================================================================================

	private long cuentaId() {
		return jdbc.queryForObject("""
				SELECT c.id FROM cuenta c JOIN platform_role p ON p.account_id = c.id
				 WHERE p.active = 1 AND p.reason LIKE 'Bootstrap de plataforma%'
				""", Long.class);
	}

	private Map<String, Object> cuentaDePlataforma() {
		return jdbc.queryForMap(
				"SELECT email, email_normalizado, estado, password_hash FROM cuenta WHERE id = ?",
				cuentaId());
	}

	private long tokenDeActivacionVigente() {
		return jdbc.queryForObject("""
				SELECT id FROM token_verificacion
				 WHERE cuenta_id = ? AND tipo = 'ACTIVACION' AND usado_en IS NULL
				   AND invalidado_en IS NULL AND expira_en > UTC_TIMESTAMP(6)
				""", Long.class, cuentaId());
	}

	private int filas(String tabla) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + tabla, Integer.class);
	}
}
