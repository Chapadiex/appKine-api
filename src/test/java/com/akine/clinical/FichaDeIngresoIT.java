package com.akine.clinical;

import com.akine.TestcontainersConfiguration;
import com.akine.testsupport.InsertarConId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ficha de ingreso por HTTP real y contra PostgreSQL: ficha declarada y revisada, version,
 * primera alta concurrente, enfermedades de la lista y catalogo de ocupaciones.
 * Mismo fixture que {@code DatosDeclaradosIT}: profesional y secretaria de la misma sede.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "akine.security.rate-limit.enabled=false")
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class FichaDeIngresoIT {

	private static final String PASSWORD = "Sintetica-Akine-2026";
	private static final String JUSTIFICACION = "X-Justificacion-Acceso";
	private static final String MOTIVO = "Carga de la ficha de ingreso";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort private int puerto;
	@Autowired private DataSource dataSource;

	private JdbcTemplate jdbc;
	private HttpClient http;

	@BeforeEach
	void preparar() {
		jdbc = new JdbcTemplate(dataSource);
		http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	}

	@Test
	@DisplayName("la ficha declarada queda sin revisar, con IMC, y suma al aviso de la historia")
	void declararFicha_quedaSinRevisar_conImc() {
		Centro c = crearCentro("imc");
		long personaId = crearPaciente(c.profesional());

		Respuesta r = put(ficha(personaId), c.secretaria().token(), ficha(null, "72", "168", null));
		assertThat(r.status()).as(r.body()).isEqualTo(200);
		assertThat(r.json().get("imc").decimalValue()).isEqualByComparingTo("25.5");
		assertThat(r.json().get("origen").asString()).isEqualTo("DECLARADO");
		assertThat(nulo(r.json().path("revisadoEn"))).isTrue();

		Respuesta vista = get(declarados(personaId), c.secretaria().token());
		assertThat(vista.json().get("ficha").get("pesoKg").decimalValue()).isEqualByComparingTo("72");
		Respuesta historia = get(hc(personaId), c.profesional().token());
		assertThat(historia.json().get("declaradosSinRevisar").asLong()).isGreaterThanOrEqualTo(1);
	}

	@Test
	@DisplayName("sin peso el IMC es null y no un error")
	void fichaSinPeso_imcNull() {
		Centro c = crearCentro("sinpeso");
		long personaId = crearPaciente(c.profesional());
		Respuesta r = put(ficha(personaId), c.secretaria().token(), ficha(null, null, "168", null));
		assertThat(r.status()).as(r.body()).isEqualTo(200);
		assertThat(nulo(r.json().path("imc"))).isTrue();
	}

	@Test
	@DisplayName("revisada; editada por quien declara vuelve a sin revisar; editada por el profesional queda revisada")
	void profesionalRevisa_yDeclaranteEdita_vuelveSinRevisar() {
		Centro c = crearCentro("revision");
		long personaId = crearPaciente(c.profesional());
		Respuesta alta = put(ficha(personaId), c.secretaria().token(), ficha(null, "70", "170", null));
		assertThat(alta.status()).as(alta.body()).isEqualTo(200);

		Respuesta revisada = put(hc(personaId) + "/ficha/revision", c.profesional().token(), null);
		assertThat(revisada.status()).as(revisada.body()).isEqualTo(200);
		assertThat(nulo(revisada.json().path("revisadoEn"))).isFalse();
		long version = revisada.json().get("version").asLong();

		Respuesta editada = put(ficha(personaId), c.secretaria().token(), ficha(version, "71", "170", null));
		assertThat(editada.status()).as(editada.body()).isEqualTo(200);
		assertThat(nulo(editada.json().path("revisadoEn")))
				.as("la edicion de quien declara vuelve a pedir revision").isTrue();

		Respuesta delProfesional = put(hc(personaId) + "/ficha", c.profesional().token(),
				ficha(editada.json().get("version").asLong(), "72", "170", null));
		assertThat(delProfesional.status()).as(delProfesional.body()).isEqualTo(200);
		assertThat(delProfesional.json().get("origen").asString()).isEqualTo("PROFESIONAL");
		assertThat(nulo(delProfesional.json().path("revisadoEn"))).isFalse();
	}

	@Test
	@DisplayName("guardar con una version vieja es 409 concurrent-modification")
	void versionVieja_409() {
		Centro c = crearCentro("version");
		long personaId = crearPaciente(c.profesional());
		Respuesta alta = put(ficha(personaId), c.secretaria().token(), ficha(null, "70", null, null));
		long version = alta.json().get("version").asLong();
		assertThat(put(ficha(personaId), c.secretaria().token(), ficha(version, "71", null, null)).status())
				.isEqualTo(200);

		Respuesta vieja = put(ficha(personaId), c.secretaria().token(), ficha(version, "72", null, null));
		assertThat(vieja.status()).as(vieja.body()).isEqualTo(409);
		assertThat(tipo(vieja)).endsWith("concurrent-modification");
	}

	@Test
	@DisplayName("dos primeras altas a la vez: una gana y la otra recibe 409, nunca 500")
	void primerAltaConcurrente_unoGana_otro409() throws Exception {
		Centro c = crearCentro("carrera");
		long personaId = crearPaciente(c.profesional());
		put(hc(personaId), c.profesional().token(), null);
		CountDownLatch largada = new CountDownLatch(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Callable<Integer> alta = () -> {
				largada.await();
				return put(ficha(personaId), c.secretaria().token(), ficha(null, "70", "170", null)).status();
			};
			Future<Integer> a = pool.submit(alta);
			Future<Integer> b = pool.submit(alta);
			largada.countDown();
			assertThat(List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(200, 409);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM historia_clinica_ficha f
				  JOIN historia_clinica h ON h.id = f.historia_clinica_id WHERE h.persona_id = ?
				""", Long.class, personaId)).isEqualTo(1);
	}

	@Test
	@DisplayName("peso fuera de rango es 400")
	void pesoFueraDeRango_400() {
		Centro c = crearCentro("rango");
		long personaId = crearPaciente(c.profesional());
		assertThat(put(ficha(personaId), c.secretaria().token(), ficha(null, "0", null, null)).status())
				.isEqualTo(400);
	}

	@Test
	@DisplayName("tildar dos veces la misma enfermedad devuelve el mismo antecedente; codigo sin MEDICO es 400")
	void enfermedadTildadaDosVeces_mismoAntecedente() {
		Centro c = crearCentro("tilde");
		long personaId = crearPaciente(c.profesional());
		String cuerpo = "{\"tipo\":\"MEDICO\",\"descripcion\":\"Diabetes\",\"codigo\":\"DIABETES\"}";
		Respuesta primera = post(declarados(personaId) + "/antecedentes", c.secretaria().token(), cuerpo);
		Respuesta segunda = post(declarados(personaId) + "/antecedentes", c.secretaria().token(), cuerpo);
		assertThat(primera.status()).as(primera.body()).isEqualTo(201);
		assertThat(segunda.json().get("id").asLong()).isEqualTo(primera.json().get("id").asLong());
		assertThat(primera.json().get("codigo").asString()).isEqualTo("DIABETES");

		Respuesta mal = post(declarados(personaId) + "/antecedentes", c.secretaria().token(),
				"{\"tipo\":\"ALERGIA\",\"descripcion\":\"x\",\"codigo\":\"DIABETES\"}");
		assertThat(mal.status()).as(mal.body()).isEqualTo(400);

		Respuesta cirugia = post(declarados(personaId) + "/antecedentes", c.secretaria().token(),
				"{\"tipo\":\"QUIRURGICO\",\"descripcion\":\"Meniscectomia\",\"zona\":\"Rodilla derecha\","
						+ "\"anio\":2019,\"lugar\":\"Hospital Privado\"}");
		assertThat(cirugia.status()).as(cirugia.body()).isEqualTo(201);
		assertThat(cirugia.json().get("zona").asString()).isEqualTo("Rodilla derecha");
		assertThat(cirugia.json().get("anio").asInt()).isEqualTo(2019);
	}

	@Test
	@DisplayName("ocupacion con otro formato devuelve la global; una nueva queda propia del centro")
	void ocupacionNormalizada_devuelveLaExistente() {
		Centro c = crearCentro("ocupacion");
		Respuesta lista = get("/api/v1/ocupaciones", c.secretaria().token());
		assertThat(lista.status()).as(lista.body()).isEqualTo(200);
		long docente = -1;
		for (JsonNode o : lista.json()) {
			if ("Docente".equals(o.get("nombre").asString())) {
				docente = o.get("id").asLong();
			}
		}
		assertThat(docente).as("el seed trae Docente").isPositive();

		Respuesta igual = post("/api/v1/ocupaciones", c.secretaria().token(), "{\"nombre\":\"  dócente \"}");
		assertThat(igual.status()).as(igual.body()).isEqualTo(200);
		assertThat(igual.json().get("id").asLong()).isEqualTo(docente);

		Respuesta nueva = post("/api/v1/ocupaciones", c.secretaria().token(), "{\"nombre\":\"Guardavidas\"}");
		assertThat(nueva.status()).as(nueva.body()).isEqualTo(201);
		assertThat(nueva.json().get("propia").asBoolean()).isTrue();
		assertThat(get("/api/v1/ocupaciones", c.profesional().token()).body()).contains("Guardavidas");

		long personaId = crearPaciente(c.profesional());
		Respuesta conOcupacion = put(ficha(personaId), c.secretaria().token(),
				"{\"ocupacionId\":" + nueva.json().get("id").asLong() + "}");
		assertThat(conOcupacion.status()).as(conOcupacion.body()).isEqualTo(200);
		assertThat(conOcupacion.json().get("ocupacionNombre").asString()).isEqualTo("Guardavidas");
	}

	@Test
	@DisplayName("ver la ficha desde la historia queda auditado como acceso a la historia")
	void verFicha_seAudita() {
		Centro c = crearCentro("auditoria");
		long personaId = crearPaciente(c.profesional());
		assertThat(put(ficha(personaId), c.secretaria().token(), ficha(null, "70", null, null)).status())
				.isEqualTo(200);
		Respuesta vista = get(hc(personaId) + "/ficha", c.profesional().token());
		assertThat(vista.status()).as(vista.body()).isEqualTo(200);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND event_type = 'HISTORIA_CLINICA_ACCESSED'
				   AND details::text LIKE '%FICHA%'
				""", Integer.class, c.profesional().organizationId())).isEqualTo(1);
	}

	@Test
	@DisplayName("tildar una enfermedad que ya cargo el profesional: 409 sin devolver su contenido")
	void tildarLoDelProfesional_409SinContenido() {
		Centro c = crearCentro("fuga");
		long personaId = crearPaciente(c.profesional());
		put(hc(personaId), c.profesional().token(), null);
		Respuesta delProfesional = post(hc(personaId) + "/antecedentes", c.profesional().token(),
				"{\"tipo\":\"MEDICO\",\"descripcion\":\"DBT2 insulinodependiente\",\"codigo\":\"DIABETES\"}");
		assertThat(delProfesional.status()).as(delProfesional.body()).isEqualTo(201);

		Respuesta tilde = post(declarados(personaId) + "/antecedentes", c.secretaria().token(),
				"{\"tipo\":\"MEDICO\",\"descripcion\":\"Diabetes\",\"codigo\":\"DIABETES\"}");
		assertThat(tilde.status()).as(tilde.body()).isEqualTo(409);
		assertThat(tipo(tilde)).endsWith("dato-ya-en-historia");
		assertThat(tilde.body()).doesNotContain("insulinodependiente");
	}

	private static boolean nulo(JsonNode nodo) {
		return nodo.isMissingNode() || nodo.isNull();
	}

	private static String ficha(long personaId) {
		return declarados(personaId) + "/ficha";
	}

	private static String ficha(Long version, String peso, String altura, Long ocupacionId) {
		return "{\"pesoKg\":" + peso + ",\"alturaCm\":" + altura + ",\"ocupacionId\":" + ocupacionId
				+ ",\"version\":" + version + "}";
	}

	// =================================================================================

	// Fixture: centro con profesional y secretaria, pacientes y HTTP
	// =================================================================================

	private record Usuario(long organizationId, long consultorioId, long cuentaId, String token) {
	}

	private record Centro(Usuario profesional, Usuario secretaria) {
	}

	private record Respuesta(int status, String body) {

		JsonNode json() {
			return JSON.readTree(body);
		}
	}

	private static String hc(long personaId) {
		return "/api/v1/historias-clinicas/por-persona/" + personaId;
	}

	private static String declarados(long personaId) {
		return "/api/v1/personas/" + personaId + "/datos-declarados";
	}

	private static String tipo(Respuesta r) {
		return r.json().get("type").asString();
	}

	private Respuesta declararAntecedente(Centro c, long personaId, String descripcion) {
		return post(declarados(personaId) + "/antecedentes", c.secretaria().token(),
				"{\"tipo\":\"ALERGIA\",\"descripcion\":\"" + descripcion + "\"}");
	}

	private Respuesta revisarAntecedente(Centro c, long personaId, long antecedenteId) {
		return put(hc(personaId) + "/antecedentes/" + antecedenteId + "/revision",
				c.profesional().token(), null);
	}

	private Respuesta declararAdjunto(Centro c, long personaId, byte[] archivo, String categoria, String titulo) {
		return multipart(declarados(personaId) + "/adjuntos", c.secretaria().token(), archivo,
				categoria, titulo);
	}

	private Respuesta subirComoProfesional(Centro c, long historiaId, byte[] archivo) {
		return multipart("/api/v1/historias-clinicas/" + historiaId + "/adjuntos", c.profesional().token(),
				archivo, "ESTUDIO", "Titulo del profesional");
	}

	private long abrirHistoria(Centro c, long personaId) {
		Respuesta r = put(hc(personaId), c.profesional().token(), null);
		assertThat(r.status()).as(r.body()).isEqualTo(200);
		return r.json().get("id").asLong();
	}

	/** Un PDF sintetico: la lista blanca mira la firma {@code %PDF-} del byte cero. */
	private static byte[] pdf(String marca) {
		return ("%PDF-1.7\n% " + marca + " " + UUID.randomUUID() + "\n").getBytes(StandardCharsets.UTF_8);
	}

	private long historiasDe(long personaId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM historia_clinica WHERE persona_id = ?", Long.class, personaId);
	}

	private long versionDeAntecedente(long id) {
		return jdbc.queryForObject(
				"SELECT version FROM historia_clinica_antecedente WHERE id = ?", Long.class, id);
	}

	/** El profesional funda el centro; la secretaria se registra aparte y se suma a su sede. */
	private Centro crearCentro(String etiqueta) {
		Usuario profesional = crearTenant(etiqueta, "PROFESIONAL");
		Usuario secretaria = crearMiembro(etiqueta + "-sec", profesional, "ADMINISTRATIVO");
		return new Centro(profesional, secretaria);
	}

	private Usuario crearTenant(String etiqueta, String rol) {
		String email = registrar(etiqueta);
		long cuentaId = cuentaDe(email);
		jdbc.update("UPDATE membership SET role_code = ? WHERE account_id = ?", rol, cuentaId);
		return iniciarSesion(email, cuentaId, null);
	}

	/**
	 * Una cuenta propia con membership en la sede del otro usuario. Su organizacion de alta queda
	 * sin usar; el contexto que se elige es el de {@code en}.
	 */
	private Usuario crearMiembro(String etiqueta, Usuario en, String rol) {
		String email = registrar(etiqueta);
		long cuentaId = cuentaDe(email);
		InsertarConId.insertar(jdbc, """
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, false, (CURRENT_TIMESTAMP - INTERVAL '1 day'),
				        'ACTIVA', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", en.organizationId(), en.consultorioId(), cuentaId, rol);
		return iniciarSesion(email, cuentaId, en);
	}

	private String registrar(String etiqueta) {
		String email = etiqueta + "-" + UUID.randomUUID() + "@ejemplo.test";
		Respuesta alta = postSinMotivo("/api/v1/auth/register",
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\","
						+ "\"firstName\":\"Sintetica\",\"lastName\":\"DePrueba\","
						+ "\"organizationName\":\"Centro " + etiqueta + "\"}",
				UUID.randomUUID().toString());
		assertThat(alta.status()).as("alta self-service: %s", alta.body()).isEqualTo(202);
		jdbc.update("UPDATE cuenta SET estado = 'ACTIVA' WHERE email_normalizado = ?",
				email.strip().toLowerCase(Locale.ROOT));
		return email;
	}

	private long cuentaDe(String email) {
		return jdbc.queryForObject("SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class,
				email.strip().toLowerCase(Locale.ROOT));
	}

	/** Login y seleccion de contexto: el de {@code en} si viene, si no el primero. */
	private Usuario iniciarSesion(String email, long cuentaId, Usuario en) {
		Respuesta login = post("/api/v1/auth/login", null,
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
		assertThat(login.status()).as("login: %s", login.body()).isEqualTo(200);
		String preContexto = login.json().get("accessToken").asString();

		JsonNode elegido = null;
		for (JsonNode contexto : get("/api/v1/me/contexts", preContexto).json()) {
			if (en == null || (contexto.get("organizationId").asLong() == en.organizationId()
					&& contexto.get("consultorioId").asLong() == en.consultorioId())) {
				elegido = contexto;
				break;
			}
		}
		assertThat(elegido).as("el contexto de la sede compartida esta disponible").isNotNull();
		long organizationId = elegido.get("organizationId").asLong();
		long consultorioId = elegido.get("consultorioId").asLong();

		Respuesta sesion = post("/api/v1/auth/context", preContexto,
				"{\"organizationId\":" + organizationId + ",\"consultorioId\":" + consultorioId + "}");
		assertThat(sesion.status()).as("seleccion de contexto: %s", sesion.body()).isEqualTo(200);
		return new Usuario(organizationId, consultorioId, cuentaId, sesion.json().get("accessToken").asString());
	}

	private long crearPaciente(Usuario usuario) {
		long personaId = crearPersonaSinPerfil(usuario);
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, CURRENT_TIMESTAMP, ?, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", usuario.organizationId(), personaId, usuario.cuentaId());
		return personaId;
	}

	private long crearPersonaSinPerfil(Usuario usuario) {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);
		return InsertarConId.insertar(jdbc, """
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave,
				                     nombre_clave, active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', true, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", usuario.organizationId(), "Paciente" + sufijo, ("PACIENTE" + sufijo).toUpperCase());
	}

	// ---------------------------------------------------------------------------------
	// HTTP
	// ---------------------------------------------------------------------------------

	private Respuesta get(String ruta, String token) {
		return enviar(constructor(ruta, token, MOTIVO).GET().build());
	}

	private Respuesta put(String ruta, String token, String cuerpo) {
		return enviar(constructor(ruta, token, MOTIVO)
				.PUT(HttpRequest.BodyPublishers.ofString(cuerpo == null ? "" : cuerpo)).build());
	}

	private Respuesta post(String ruta, String token, String cuerpo) {
		return enviar(constructor(ruta, token, MOTIVO)
				.POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build());
	}

	private Respuesta postSinMotivo(String ruta, String cuerpo, String claveIdempotencia) {
		return enviar(constructor(ruta, null, null)
				.header("Idempotency-Key", claveIdempotencia)
				.POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build());
	}

	/** Multipart a mano: {@code archivo} como parte binaria, {@code categoria} y {@code titulo} como campos. */
	private Respuesta multipart(String ruta, String token, byte[] archivo, String categoria, String titulo) {
		String limite = "----akine" + UUID.randomUUID();
		ByteArrayOutputStream cuerpo = new ByteArrayOutputStream();
		escribir(cuerpo, "--" + limite + "\r\nContent-Disposition: form-data; name=\"categoria\"\r\n\r\n"
				+ categoria + "\r\n");
		escribir(cuerpo, "--" + limite + "\r\nContent-Disposition: form-data; name=\"titulo\"\r\n\r\n"
				+ titulo + "\r\n");
		escribir(cuerpo, "--" + limite + "\r\nContent-Disposition: form-data; name=\"archivo\"; "
				+ "filename=\"documento.pdf\"\r\nContent-Type: application/pdf\r\n\r\n");
		cuerpo.writeBytes(archivo);
		escribir(cuerpo, "\r\n--" + limite + "--\r\n");

		HttpRequest.Builder builder = HttpRequest
				.newBuilder(URI.create("http://localhost:" + puerto + ruta))
				.header("Content-Type", "multipart/form-data; boundary=" + limite)
				.header("Authorization", "Bearer " + token)
				.header(JUSTIFICACION, MOTIVO);
		return enviar(builder.POST(HttpRequest.BodyPublishers.ofByteArray(cuerpo.toByteArray())).build());
	}

	private static void escribir(ByteArrayOutputStream destino, String texto) {
		destino.writeBytes(texto.getBytes(StandardCharsets.UTF_8));
	}

	private HttpRequest.Builder constructor(String ruta, String token, String justificacion) {
		HttpRequest.Builder builder = HttpRequest
				.newBuilder(URI.create("http://localhost:" + puerto + ruta))
				.header("Content-Type", "application/json");
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		if (justificacion != null) {
			builder.header(JUSTIFICACION, justificacion);
		}
		return builder;
	}

	private Respuesta enviar(HttpRequest request) {
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			return new Respuesta(response.statusCode(), response.body());
		}
		catch (IOException e) {
			throw new IllegalStateException("Fallo el request HTTP a " + request.uri(), e);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Request interrumpido a " + request.uri(), e);
		}
	}
}
