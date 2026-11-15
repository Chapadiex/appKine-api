package com.akine.assistant.api;

import com.akine.TestcontainersConfiguration;
import com.akine.assistant.domain.port.ModeloDeLenguaje;
import com.akine.assistant.infrastructure.ModeloDelAsistenteDePrueba;
import com.akine.organization.application.MembershipService;
import com.akine.organization.spi.DirectMembershipCommand;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.PersonaAltaCommand;
import com.akine.person.application.PersonaService;
import com.akine.person.domain.TipoDocumento;
import org.junit.jupiter.api.AfterEach;
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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El asistente de datos de punta a punta: HTTP real, login y contexto por el camino real,
 * PostgreSQL, y las herramientas llamando a los servicios reales de cada modulo. Solo el modelo es
 * de prueba ({@link ModeloDelAsistenteDePrueba}), porque lo que se prueba aca son las reglas que no
 * dependen de el: permiso, aislamiento entre organizaciones, que el SQL de cada herramienta corra
 * contra el motor y que la auditoria nunca guarde la pregunta.
 *
 * <p>La precision del modelo se mide aparte, contra Ollama real ({@code InterpretadorContraOllamaTest}).
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"akine.security.rate-limit.enabled=false",
				"akine.asistente.enabled=true",
				"akine.asistente.modelo.tipo=prueba"})
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsistenteIT {

	private static final String PASSWORD = "Sintetica-Akine-2026";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort int puerto;
	@Autowired JdbcTemplate jdbc;
	@Autowired MembershipService membershipService;
	@Autowired PersonaService personas;
	@Autowired ModeloDeLenguaje modeloDeLenguaje;

	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private ModeloDelAsistenteDePrueba modelo;
	private Sesion centroA;
	private Sesion centroB;
	private String apellidoEnA;
	private String apellidoEnB;

	@BeforeAll
	void sembrar() throws Exception {
		modelo = (ModeloDelAsistenteDePrueba) modeloDeLenguaje;
		centroA = altaCompleta("asistente-a");
		centroB = altaCompleta("asistente-b");
		apellidoEnA = "Zarate" + sufijo();
		apellidoEnB = "Ulloa" + sufijo();
		altaDePersona(centroA, apellidoEnA);
		altaDePersona(centroB, apellidoEnB);
	}

	@AfterEach
	void modeloPorDefecto() {
		modelo.reiniciar();
	}

	@Test
	void responde_la_asistencia_con_la_persona_del_padron_y_el_sql_real_de_turnos() throws Exception {
		modelo.responderJson(interpretacion("ASISTENCIA_PERSONA", apellidoEnA, null, "ESTA_SEMANA", null));

		JsonNode r = preguntar(centroA, centroA.consultorioId(), "¿" + apellidoEnA + " vino esta semana?", 200);

		assertThat(r.get("resultado").asString()).isEqualTo("RESPONDIDA");
		assertThat(r.get("texto").asString()).startsWith(apellidoEnA + ", Ana no tiene turnos entre el ");
		assertThat(r.get("enlace").asString()).startsWith("/pacientes/");
	}

	@Test
	void agenda_clases_y_reporte_corren_contra_el_motor() throws Exception {
		modelo.responderJson(interpretacion("AGENDA_DEL_DIA", null, null, "MANANA", null));
		assertThat(preguntar(centroA, centroA.consultorioId(), "¿quiénes vienen mañana?", 200)
				.get("texto").asString()).contains("no hay turnos");

		modelo.responderJson(interpretacion("ASISTENTES_CLASE", null, "yoga", "HOY", null));
		assertThat(preguntar(centroA, centroA.consultorioId(), "¿quiénes vinieron a yoga hoy?", 200)
				.get("texto").asString()).startsWith("No encontré una clase de \"yoga\"");

		modelo.responderJson(interpretacion("REPORTE", null, null, "ESTE_MES", "TURNOS"));
		JsonNode reporte = preguntar(centroA, centroA.consultorioId(), "¿cuántos turnos hubo este mes?", 200);
		assertThat(reporte.get("resultado").asString()).isEqualTo("RESPONDIDA");
		assertThat(reporte.get("filas").size()).isPositive();
	}

	@Test
	void una_persona_de_otra_organizacion_no_existe_para_el_asistente() throws Exception {
		modelo.responderJson(interpretacion("ASISTENCIA_PERSONA", apellidoEnB, null, null, null));

		JsonNode r = preguntar(centroA, centroA.consultorioId(), "¿" + apellidoEnB + " vino?", 200);

		assertThat(r.get("resultado").asString()).isEqualTo("PERSONA_NO_ENCONTRADA");
	}

	@Test
	void la_sede_de_otra_organizacion_es_404() throws Exception {
		modelo.responderJson(interpretacion("AGENDA_DEL_DIA", null, null, "HOY", null));

		JsonNode r = preguntar(centroB, centroA.consultorioId(), "¿quiénes vienen hoy?", 404);

		assertThat(r.get("type").asString()).endsWith("/not-found");
	}

	@Test
	void un_paciente_no_tiene_la_puerta() throws Exception {
		Sesion paciente = conRolEn(centroA, "asistente-paciente", "PACIENTE");

		JsonNode r = preguntar(paciente, centroA.consultorioId(), "¿quiénes vienen hoy?", 403);

		assertThat(r.get("requiredPermission").asString()).isEqualTo("asistente:use");
	}

	@Test
	void una_pregunta_vacia_es_400() throws Exception {
		HttpResponse<String> r = post("/api/v1/consultorios/" + centroA.consultorioId() + "/asistente/consultas",
				centroA.token(), "{\"pregunta\":\"\"}");
		assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
	}

	@Test
	void la_auditoria_registra_la_consulta_sin_la_pregunta() throws Exception {
		String marca = "Marca" + sufijo();
		modelo.responderJson(interpretacion("AGENDA_DEL_DIA", null, null, "HOY", null));

		preguntar(centroA, centroA.consultorioId(), "¿quiénes vienen hoy? " + marca, 200);

		Integer conMarca = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE event_type = 'ASISTENTE_CONSULTA' "
				+ "AND organization_id = ? AND details::text LIKE ?", Integer.class, centroA.organizationId(), "%" + marca + "%");
		Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE event_type = 'ASISTENTE_CONSULTA' "
				+ "AND organization_id = ? AND details::text LIKE '%AGENDA_DEL_DIA%'", Integer.class, centroA.organizationId());
		assertThat(conMarca).isZero();
		assertThat(total).isPositive();
	}

	// ------------------------------------------------------------------ soporte

	private static String interpretacion(String intencion, String persona, String clase, String periodo, String reporte) {
		return "{\"intencion\":" + q(intencion) + ",\"persona\":" + q(persona) + ",\"clase\":" + q(clase)
				+ ",\"periodo\":" + q(periodo) + ",\"reporte\":" + q(reporte) + "}";
	}

	private static String q(String v) {
		return v == null ? "null" : "\"" + v + "\"";
	}

	private static String sufijo() {
		// Solo letras: la busqueda del padron normaliza el nombre.
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < 6; i++) {
			s.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
		}
		return s.toString();
	}

	private JsonNode preguntar(Sesion sesion, long consultorioId, String pregunta, int esperado) throws Exception {
		HttpResponse<String> r = post("/api/v1/consultorios/" + consultorioId + "/asistente/consultas", sesion.token(),
				JSON.writeValueAsString(java.util.Map.of("pregunta", pregunta)));
		assertThat(r.statusCode()).as(r.body()).isEqualTo(esperado);
		return JSON.readTree(r.body());
	}

	private void altaDePersona(Sesion centro, String apellido) {
		personas.crear(new OperatingActor(centro.cuentaId(), false, centro.organizationId(), centro.consultorioId()),
				new PersonaAltaCommand(TipoDocumento.DNI, String.valueOf(20_000_000 + ThreadLocalRandom.current().nextInt(9_000_000)),
						apellido, "Ana", null, null, null, null, false));
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
		HttpResponse<String> contextos = http.send(HttpRequest.newBuilder(uri("/api/v1/me/contexts"))
				.header("Authorization", "Bearer " + preContexto).GET().build(), HttpResponse.BodyHandlers.ofString());
		JsonNode primero = JSON.readTree(contextos.body()).get(0);
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
