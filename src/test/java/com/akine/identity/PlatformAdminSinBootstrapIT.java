package com.akine.identity;

import com.akine.TestcontainersConfiguration;
import com.akine.identity.application.PlatformAdminBootstrapService;
import com.akine.identity.application.PlatformAdminBootstrapService.ResultadoBootstrap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sin {@code AKINE_BOOTSTRAP_ADMIN_EMAIL} el arranque no toca la cuenta sembrada (DP-14).
 *
 * <p>Misma configuracion de contexto que {@code PlatformRoleGrantIT}, a proposito: Spring reusa el
 * contexto y este test no paga otro arranque. Ese IT agrega cuentas de plataforma propias pero no
 * toca la sembrada, que es lo unico que se mira aca.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PlatformAdminSinBootstrapIT {

	@Autowired private JdbcTemplate jdbc;
	@Autowired private PlatformAdminBootstrapService bootstrap;

	@Test
	@DisplayName("sin la variable, la cuenta sembrada queda como la dejo V15 y sin enlace")
	void sin_variable_no_hace_nada() {
		Map<String, Object> sembrada = jdbc.queryForMap(
				"SELECT id, estado, password_hash FROM cuenta WHERE email_normalizado = 'plataforma@akine.app'");
		assertThat(sembrada.get("estado")).isEqualTo("ACTIVA");
		assertThat(sembrada.get("password_hash")).isNull();

		long id = ((Number) sembrada.get("id")).longValue();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM token_verificacion WHERE cuenta_id = ?", Integer.class, id))
				.isZero();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM audit_event WHERE event_type = 'PLATFORM_ADMIN_BOOTSTRAP'",
				Integer.class)).isZero();

		assertThat(bootstrap.ejecutar(null)).isEqualTo(ResultadoBootstrap.SIN_VARIABLE);
		assertThat(bootstrap.ejecutar("")).isEqualTo(ResultadoBootstrap.SIN_VARIABLE);
	}
}
