package com.akine.assistant.api;

import com.akine.TestcontainersConfiguration;
import com.akine.assistant.infrastructure.TranscriptorDePrueba;
import com.akine.platform.infrastructure.modelo.ModeloDeLenguajeDePrueba;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
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
 * de prueba ({@link ModeloDeLenguajeDePrueba}), porque lo que se prueba aca son las reglas que no
 * dependen de el: permiso, aislamiento entre organizaciones, que el SQL de cada herramienta corra
 * contra el motor y que la auditoria nunca guarde la pregunta.
 *
 * <p>Las consultas que pasan por el agente (herramientas, aislamiento por nombre, auditoria) las prueba
 * {@code AgenteDelAsistenteIT}; aca quedan las puertas que cortan antes del modelo y el dictado.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"akine.security.rate-limit.enabled=false",
				"akine.asistente.enabled=true",
				"akine.asistente.modelo.tipo=prueba",
				"akine.asistente.agente.proveedor=prueba",
				"akine.dictado.enabled=true",
				"akine.dictado.transcriptor.tipo=prueba"})
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

	private ModeloDeLenguajeDePrueba modelo;
	private Sesion centroA;
	private Sesion centroB;
	private String apellidoEnA;
	private String apellidoEnB;

	@BeforeAll
	void sembrar() throws Exception {
		modelo = (ModeloDeLenguajeDePrueba) modeloDeLenguaje;
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
	void la_sede_de_otra_organizacion_es_404() throws Exception {
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

		// Code review del 15/11: un elemento null en el historial terminaba en NullPointerException (500).
		HttpResponse<String> conNull = post("/api/v1/consultorios/" + centroA.consultorioId() + "/asistente/consultas",
				centroA.token(), "{\"pregunta\":\"hola\",\"historial\":[null]}");
		assertThat(conNull.statusCode()).as(conNull.body()).isEqualTo(400);
	}

	// ------------------------------------------------------------------ soporte

	private static String sufijo() {
		// Solo letras: la busqueda del padron normaliza el nombre.
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < 6; i++) {
			s.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
		}
		return s.toString();
	}

	// --- Dictado por voz (docs/diseno/AKINE-dictado-por-voz.md) -----------------------------

	@Test
	void el_dictado_transcribe_un_audio_por_multipart_y_rechaza_lo_que_no_es_audio() throws Exception {
		HttpResponse<String> ok = subirAudio(centroA, centroA.consultorioId(), "audio/webm;codecs=opus", new byte[] {1, 2, 3});
		assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);
		assertThat(JSON.readTree(ok.body()).get("texto").asString()).isEqualTo(TranscriptorDePrueba.TEXTO);

		HttpResponse<String> imagen = subirAudio(centroA, centroA.consultorioId(), "image/png", new byte[] {1});
		assertThat(imagen.statusCode()).as(imagen.body()).isEqualTo(400);
		assertThat(JSON.readTree(imagen.body()).get("type").asString()).endsWith("/validation-error");

		HttpResponse<String> ajena = subirAudio(centroB, centroA.consultorioId(), "audio/webm", new byte[] {1});
		assertThat(ajena.statusCode()).as(ajena.body()).isEqualTo(404);
	}

	@Test
	void la_propuesta_de_evaluacion_descarta_lo_que_no_valida() throws Exception {
		modelo.responderJson("{\"dolorEva\":14,\"dolorZona\":\"rodilla\",\"dolorLateralidad\":\"derecha\","
				+ "\"evolucion\":\"MUCHO_MEJOR\",\"objetivoSesion\":\"ganar flexion\",\"restante\":\"Trajo estudios.\"}");

		HttpResponse<String> r = post("/api/v1/consultorios/" + centroA.consultorioId() + "/dictado/evaluaciones",
				centroA.token(),
				"{\"texto\":\"Dolor catorce en rodilla derecha, mucho mejor. Objetivo ganar flexion. Trajo estudios.\"}");

		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		JsonNode p = JSON.readTree(r.body());
		assertThat(p.has("dolorEva")).isFalse();
		assertThat(p.has("evolucion")).isFalse();
		assertThat(p.get("dolorZona").asString()).isEqualTo("rodilla");
		assertThat(p.get("dolorLateralidad").asString()).isEqualTo("DERECHA");
		assertThat(p.get("objetivoSesion").asString()).isEqualTo("ganar flexion");
		// La evolucion fuera de catalogo no se pierde: la frase vuelve a las notas.
		assertThat(p.get("restante").asString()).isEqualTo("- Trajo estudios.\n- Dictado: mucho mejor");
	}

	@Test
	void un_paciente_no_puede_dictar() throws Exception {
		Sesion paciente = conRolEn(centroA, "dictado-paciente", "PACIENTE");

		HttpResponse<String> r = subirAudio(paciente, centroA.consultorioId(), "audio/webm", new byte[] {1});

		assertThat(r.statusCode()).as(r.body()).isEqualTo(403);
	}

	private HttpResponse<String> subirAudio(Sesion sesion, long consultorioId, String tipo, byte[] audio)
			throws Exception {
		String limite = "limite-" + UUID.randomUUID();
		java.io.ByteArrayOutputStream cuerpo = new java.io.ByteArrayOutputStream();
		cuerpo.writeBytes(("--" + limite + "\r\nContent-Disposition: form-data; name=\"audio\"; filename=\"a.webm\"\r\n"
				+ "Content-Type: " + tipo + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		cuerpo.writeBytes(audio);
		cuerpo.writeBytes(("\r\n--" + limite + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
		return http.send(HttpRequest.newBuilder(uri("/api/v1/consultorios/" + consultorioId + "/dictado/transcripciones"))
				.header("Authorization", "Bearer " + sesion.token())
				.header("Content-Type", "multipart/form-data; boundary=" + limite)
				.POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray()))
				.build(), HttpResponse.BodyHandlers.ofString());
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
