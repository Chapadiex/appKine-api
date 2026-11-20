package com.akine.knowledge.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.akine.TestcontainersConfiguration;
import com.akine.organization.application.MembershipService;
import com.akine.organization.spi.DirectMembershipCommand;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * El manual de punta a punta: login y contexto por el camino real, con las fichas reales del
 * classpath. Lo que importa es el filtro: una persona sin {@code caja:operate} no ve la ficha de la
 * caja por ningun camino (indice, ficha, contexto ni busqueda).
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "akine.security.rate-limit.enabled=false")
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManualReadIT {

	private static final String PASSWORD = "Sintetica-Akine-2026";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort int puerto;
	@Autowired JdbcTemplate jdbc;
	@Autowired MembershipService membershipService;

	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private Sesion administrador;
	private Sesion profesional;

	@BeforeAll
	void sembrar() throws Exception {
		administrador = altaCompleta("manual-it");
		profesional = conRolEn(administrador, "manual-it-prof", "PROFESIONAL");
	}

	@Test
	void profesionalSinCajaNoVeCajaDiaria() throws Exception {
		HttpResponse<String> indice = get("/api/v1/manual", profesional.token());
		assertThat(indice.statusCode()).as(indice.body()).isEqualTo(200);
		assertThat(indice.body()).doesNotContain("\"caja-diaria\"");

		assertThat(get("/api/v1/manual/fichas/caja-diaria", profesional.token()).statusCode()).isEqualTo(404);
		assertThat(get("/api/v1/manual/contexto?ruta=/caja", profesional.token()).statusCode()).isEqualTo(204);

		HttpResponse<String> busqueda = get("/api/v1/manual/busqueda?q=" + enc("abrir la caja"), profesional.token());
		assertThat(busqueda.statusCode()).as(busqueda.body()).isEqualTo(200);
		assertThat(busqueda.body()).doesNotContain("caja-diaria");
	}

	@Test
	void administradorConCajaLaVe() throws Exception {
		HttpResponse<String> ficha = get("/api/v1/manual/contexto?ruta=" + enc("/caja?x=1"), administrador.token());
		assertThat(ficha.statusCode()).as(ficha.body()).isEqualTo(200);
		JsonNode cuerpo = JSON.readTree(ficha.body());
		assertThat(cuerpo.get("id").asString()).isEqualTo("caja-diaria");
		assertThat(cuerpo.get("markdown").asString()).contains("## Para qué sirve");

		JsonNode encontrados = JSON.readTree(
				get("/api/v1/manual/busqueda?q=" + enc("arqueo"), administrador.token()).body());
		assertThat(encontrados.get(0).get("fichaId").asString()).isEqualTo("caja-diaria");
		assertThat(encontrados.get(0).get("enlace").asString()).isEqualTo("/caja");
	}

	@Test
	void contextoSinFichaDa204() throws Exception {
		assertThat(get("/api/v1/manual/contexto?ruta=/no-existe", administrador.token()).statusCode()).isEqualTo(204);
	}

	@Test
	void sinSedeEnContextoDa403() throws Exception {
		String preContexto = login(administrador.email());

		assertThat(get("/api/v1/manual", preContexto).statusCode()).isEqualTo(403);
	}

	// ------------------------------------------------------------------ soporte

	private static String enc(String texto) {
		return URLEncoder.encode(texto, StandardCharsets.UTF_8);
	}

	private Sesion conRolEn(Sesion centro, String etiqueta, String rol) throws Exception {
		Sesion cuenta = altaCompleta(etiqueta);
		membershipService.createDirect(centro.cuentaId(), false, centro.organizationId(),
				new DirectMembershipCommand(cuenta.cuentaId(), centro.consultorioId(), rol, "prueba sintetica"));
		String token = seleccionarContexto(login(cuenta.email()), centro.organizationId(), centro.consultorioId());
		return new Sesion(cuenta.cuentaId(), cuenta.email(), centro.organizationId(), centro.consultorioId(), token);
	}

	record Sesion(long cuentaId, String email, long organizationId, long consultorioId, String token) {
	}

	private Sesion altaCompleta(String etiqueta) throws Exception {
		String email = etiqueta + "-" + UUID.randomUUID() + "@ejemplo.test";
		String alta = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\","
				+ "\"firstName\":\"Sintetica\",\"lastName\":\"DePrueba\","
				+ "\"organizationName\":\"Centro " + etiqueta + "\"}";
		HttpResponse<String> registro = post("/api/v1/auth/register", null, alta,
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(registro.statusCode()).as(registro.body()).isEqualTo(202);
		assertThat(jdbc.update("UPDATE cuenta SET estado = 'ACTIVA' WHERE email_normalizado = ?",
				email.toLowerCase(Locale.ROOT))).isEqualTo(1);

		String preContexto = login(email);
		JsonNode primero = JSON.readTree(get("/api/v1/me/contexts", preContexto).body()).get(0);
		long organizationId = primero.get("organizationId").asLong();
		long consultorioId = primero.get("consultorioId").asLong();
		Long cuentaId = jdbc.queryForObject("SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class,
				email.toLowerCase(Locale.ROOT));
		return new Sesion(cuentaId, email, organizationId, consultorioId,
				seleccionarContexto(preContexto, organizationId, consultorioId));
	}

	private String login(String email) throws Exception {
		HttpResponse<String> login = post("/api/v1/auth/login", null,
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
		assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
		return JSON.readTree(login.body()).get("accessToken").asString();
	}

	private String seleccionarContexto(String preContexto, long organizationId, long consultorioId) throws Exception {
		HttpResponse<String> contexto = post("/api/v1/auth/context", preContexto,
				"{\"organizationId\":" + organizationId + ",\"consultorioId\":" + consultorioId + "}");
		assertThat(contexto.statusCode()).as(contexto.body()).isEqualTo(200);
		return JSON.readTree(contexto.body()).get("accessToken").asString();
	}

	private HttpResponse<String> get(String ruta, String token) throws Exception {
		return http.send(HttpRequest.newBuilder(uri(ruta)).header("Authorization", "Bearer " + token).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private HttpResponse<String> post(String ruta, String token, String cuerpo, String... headers) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri(ruta))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(cuerpo));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		if (headers.length > 0) {
			builder.headers(headers);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String ruta) {
		return URI.create("http://localhost:" + puerto + ruta);
	}
}
