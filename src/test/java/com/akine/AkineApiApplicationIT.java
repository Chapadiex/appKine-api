package com.akine;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test de integracion del baseline (AKINE-00.01).
 *
 * <p>Verifica lo que ningun test unitario puede: que la aplicacion arranca contra una base
 * MySQL vacia, que Flyway aplica la migracion inicial y que el contrato tecnico responde
 * sobre HTTP real.
 *
 * <p><b>Requiere Docker corriendo.</b> Se ejecuta con {@code ./mvnw verify}, no con
 * {@code ./mvnw test}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureRestTestClient
@Import(TestcontainersConfiguration.class)
class AkineApiApplicationIT {

	@Autowired
	private RestTestClient restTestClient;

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("La aplicacion arranca contra una base MySQL vacia")
	void el_contexto_carga() {
		assertThat(dataSource).isNotNull();
	}

	@Test
	@DisplayName("Flyway aplica la migracion inicial sobre una base vacia")
	void flyway_aplica_la_migracion_inicial() {
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		Integer migracionesExitosas = jdbc.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
		assertThat(migracionesExitosas)
				.as("flyway debe haber aplicado al menos la migracion V1")
				.isNotNull()
				.isGreaterThanOrEqualTo(1);

		String etapa = jdbc.queryForObject(
				"SELECT baseline_stage FROM platform_schema_info ORDER BY id DESC LIMIT 1",
				String.class);
		assertThat(etapa).isEqualTo("AKINE-00.01");
	}

	@Test
	@DisplayName("El esquema que produjo Flyway es el que espera la aplicacion")
	void el_esquema_es_el_esperado() {
		// ddl-auto=validate: si el contexto cargo, Hibernate ya confirmo que el esquema
		// generado por Flyway coincide con el mapeo. Esta asercion lo deja documentado.
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		Integer tablas = jdbc.queryForObject(
				"SELECT COUNT(*) FROM information_schema.tables "
						+ "WHERE table_schema = DATABASE() AND table_name = 'platform_schema_info'",
				Integer.class);
		assertThat(tablas).isEqualTo(1);
	}

	@Test
	@DisplayName("El health de Actuator responde UP con la base conectada")
	void el_health_responde_up() {
		restTestClient.get().uri("/actuator/health")
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.value(cuerpo -> assertThat(cuerpo).contains("UP"));
	}

	@Test
	@DisplayName("El contrato tecnico de version responde sobre HTTP real")
	void el_endpoint_de_version_responde() {
		restTestClient.get().uri("/api/v1/version")
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.value(cuerpo -> assertThat(cuerpo).contains("akine-api"));
	}

	@Test
	@DisplayName("springdoc publica el contrato OpenAPI")
	void publica_el_contrato_openapi() {
		restTestClient.get().uri("/v3/api-docs.yaml")
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.value(cuerpo -> assertThat(cuerpo).contains("/api/v1/version"));
	}

	@Test
	@DisplayName("Las respuestas llevan los headers de la base segura")
	void aplica_headers_de_seguridad() {
		restTestClient.get().uri("/api/v1/version")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
				.expectHeader().valueEquals("X-Frame-Options", "DENY");
	}
}
