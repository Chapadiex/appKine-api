package com.akine.platform.infrastructure.observability;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;

import com.akine.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G-4 contra la aplicacion entera: id de correlacion, scrape de Prometheus y log JSON.
 *
 * <p>{@code @AutoConfigureMetrics} hace falta porque Spring Boot apaga la exportacion de
 * metricas en los tests por default; sin el, {@code /actuator/prometheus} no existe. El log se
 * fuerza a JSON con la misma propiedad que usa un despliegue: el perfil {@code local} lo apaga.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "logging.structured.format.console=logstash")
@ActiveProfiles("local")
@AutoConfigureRestTestClient
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ObservabilidadIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private RestTestClient restTestClient;

	@Test
	@DisplayName("el X-Request-Id recibido vuelve en la respuesta; si no viene, se genera uno")
	void el_request_id_vuelve_en_la_respuesta() {
		restTestClient.get().uri("/api/v1/version")
				.header(CorrelationIdFilter.HEADER, "it-correlacion-1")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueEquals(CorrelationIdFilter.HEADER, "it-correlacion-1");

		restTestClient.get().uri("/api/v1/version")
				.exchange()
				.expectStatus().isOk()
				.expectHeader().valueMatches(CorrelationIdFilter.HEADER, "[0-9a-f-]{36}");

		// Tambien en un rechazo de la cadena de seguridad: el filtro corre antes que ella.
		restTestClient.get().uri("/api/v1/me/contexts")
				.header(CorrelationIdFilter.HEADER, "it-correlacion-401")
				.exchange()
				.expectStatus().isUnauthorized()
				.expectHeader().valueEquals(CorrelationIdFilter.HEADER, "it-correlacion-401");
	}

	@Test
	@DisplayName("en el perfil local /actuator/prometheus responde, con histograma y sin ids")
	void prometheus_expone_el_histograma_de_requests() {
		restTestClient.get().uri("/api/v1/version").exchange().expectStatus().isOk();

		String scrape = restTestClient.get().uri("/actuator/prometheus")
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.returnResult()
				.getResponseBody();

		assertThat(scrape)
				.contains("http_server_requests_seconds_bucket")
				// Los buckets de los SLO de ADR-0016: 300 ms y 800 ms.
				.contains("le=\"0.3\"", "le=\"0.8\"")
				.contains("uri=\"/api/v1/version\"")
				.contains("application=\"akine-api\"")
				.doesNotContain("organizationId", "requestId", "it-correlacion");
	}

	@Test
	@DisplayName("el log sale en JSON con traceId, spanId y requestId en la misma linea")
	void el_log_json_lleva_la_correlacion(CapturedOutput salida) {
		// Un refresh con la cookie y sin Origin lo rechaza OriginCsrfFilter con un WARN: es un
		// log emitido DENTRO del request, que es lo que tiene que llevar la correlacion.
		restTestClient.post().uri("/api/v1/auth/refresh")
				.header(CorrelationIdFilter.HEADER, "it-log-json-1")
				.cookie("akine_rt", "cualquiera")
				.exchange()
				.expectStatus().isForbidden();

		String linea = Arrays.stream(salida.getOut().split("\\R"))
				.filter(l -> l.contains("it-log-json-1"))
				.findFirst()
				.orElseThrow(() -> new AssertionError("ninguna linea de log con el request id"));

		JsonNode evento = JSON.readTree(linea);
		assertThat(evento.path("requestId").asString()).isEqualTo("it-log-json-1");
		assertThat(evento.path("traceId").asString()).matches("[0-9a-f]{32}");
		assertThat(evento.path("spanId").asString()).matches("[0-9a-f]{16}");
		assertThat(evento.path("level").asString()).isEqualTo("WARN");
		assertThat(evento.path("service").asString()).isEqualTo("akine-api");
		assertThat(evento.has("@timestamp")).isTrue();
		assertThat(evento.has("message")).isTrue();
		// El valor de la cookie de sesion nunca llega al log.
		assertThat(linea).doesNotContain("cualquiera");
	}
}
