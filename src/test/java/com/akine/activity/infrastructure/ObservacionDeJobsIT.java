package com.akine.activity.infrastructure;

import com.akine.TestcontainersConfiguration;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los jobs de F9 contra el {@code /actuator/prometheus} y el log JSON de verdad (AKINE-09.03,
 * docs/observabilidad.md §7). Mismo contexto que {@code ObservabilidadIT}: mismas anotaciones.
 *
 * <p>Lo que un unitario de {@link ObservacionDeJobs} no puede ver: que los cuatro workers esten
 * cableados, que Spring Boot convierta la observacion en el timer {@code akine_job_seconds}, que
 * los contadores aparezcan en cero antes de la primera pasada y que la linea de resumen salga como
 * JSON con sus campos y el {@code traceId} de la pasada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "logging.structured.format.console=logstash")
@ActiveProfiles("local")
@AutoConfigureRestTestClient
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class ObservacionDeJobsIT {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired private RestTestClient restTestClient;
	@Autowired private VencimientoDePasesWorker vencimiento;
	@Autowired private RenovacionDeAbonosWorker renovacion;
	@Autowired private AvisosDePasesWorker avisos;

	@Test
	@DisplayName("cada job publica sus contadores por resultado y su timer, sin ids en las etiquetas")
	void los_jobs_publican_metricas() {
		vencimiento.tick();
		renovacion.tick();
		avisos.tick();

		String scrape = restTestClient.get().uri("/actuator/prometheus")
				.exchange()
				.expectStatus().isOk()
				.expectBody(String.class)
				.returnResult()
				.getResponseBody();

		for (String job : ObservacionDeJobs.JOBS) {
			for (String resultado : new String[] {"procesado", "omitido", "fallido"}) {
				assertThat(scrape).as("contador %s/%s", job, resultado).containsPattern(
						"akine_job_items_total\\{[^}]*job=\"" + job + "\"[^}]*resultado=\"" + resultado + "\"");
			}
			assertThat(scrape).as("timer de %s", job)
					.containsPattern("akine_job_seconds_count\\{[^}]*job=\"" + job + "\"");
		}
		assertThat(scrape).doesNotContain("paseId", "personaId", "organizationId");
	}

	@Test
	@DisplayName("la pasada deja una linea JSON con el job, las cuentas, la duracion y el traceId")
	void la_pasada_deja_un_log_estructurado(CapturedOutput salida) {
		vencimiento.tick();

		String linea = Arrays.stream(salida.getOut().split("\\R"))
				.filter(l -> l.contains("Pasada de job terminada") && l.contains("pases-vencimiento"))
				.reduce((primera, ultima) -> ultima)
				.orElseThrow(() -> new AssertionError("ninguna linea de resumen del vencimiento"));

		JsonNode evento = JSON.readTree(linea);
		assertThat(evento.path("job").asString()).isEqualTo("pases-vencimiento");
		assertThat(evento.has("procesados")).isTrue();
		assertThat(evento.has("omitidos")).isTrue();
		assertThat(evento.has("fallidos")).isTrue();
		assertThat(evento.has("duracionMs")).isTrue();
		assertThat(evento.path("traceId").asString()).matches("[0-9a-f]{32}");
		assertThat(evento.path("service").asString()).isEqualTo("akine-api");
	}
}
