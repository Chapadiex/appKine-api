package com.akine.assistant.api;

import com.akine.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Con la configuracion por defecto el asistente esta apagado: 409 {@code asistente-deshabilitado},
 * aunque el actor tenga el permiso. Sin propiedades propias a proposito, para reusar el contexto de
 * Spring que comparten los demas IT y no pagar un arranque mas.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "akine.security.rate-limit.enabled=false")
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AsistenteApagadoIT {

	private static final String PASSWORD = "Sintetica-Akine-2026";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort int puerto;
	@Autowired JdbcTemplate jdbc;

	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	void apagado_responde_409_aunque_el_actor_tenga_el_permiso() throws Exception {
		String email = "asistente-apagado-" + UUID.randomUUID() + "@ejemplo.test";
		HttpResponse<String> registro = post("/api/v1/auth/register", null, "{\"email\":\"" + email
				+ "\",\"password\":\"" + PASSWORD + "\",\"firstName\":\"Sintetica\",\"lastName\":\"DePrueba\","
				+ "\"organizationName\":\"Centro apagado\"}", "Idempotency-Key", UUID.randomUUID().toString());
		assertThat(registro.statusCode()).as(registro.body()).isEqualTo(202);
		jdbc.update("UPDATE cuenta SET estado = 'ACTIVA' WHERE email_normalizado = ?", email.toLowerCase(Locale.ROOT));
		String pre = JSON.readTree(post("/api/v1/auth/login", null,
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}").body()).get("accessToken").asString();
		var contexto = JSON.readTree(http.send(HttpRequest.newBuilder(uri("/api/v1/me/contexts"))
				.header("Authorization", "Bearer " + pre).GET().build(), HttpResponse.BodyHandlers.ofString()).body()).get(0);
		long org = contexto.get("organizationId").asLong();
		long sede = contexto.get("consultorioId").asLong();
		String token = JSON.readTree(post("/api/v1/auth/context", pre,
				"{\"organizationId\":" + org + ",\"consultorioId\":" + sede + "}").body()).get("accessToken").asString();

		HttpResponse<String> r = post("/api/v1/consultorios/" + sede + "/asistente/consultas", token,
				"{\"pregunta\":\"¿quiénes vienen hoy?\"}");

		assertThat(r.statusCode()).as(r.body()).isEqualTo(409);
		assertThat(JSON.readTree(r.body()).get("type").asString()).endsWith("/asistente-deshabilitado");
	}

	private HttpResponse<String> post(String ruta, String token, String cuerpo, String... headers) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(uri(ruta)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(cuerpo));
		if (token != null) {
			b.header("Authorization", "Bearer " + token);
		}
		if (headers.length > 0) {
			b.headers(headers);
		}
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String ruta) {
		return URI.create("http://localhost:" + puerto + ruta);
	}
}
