package com.akine.knowledge.api;

import com.akine.TestcontainersConfiguration;
import com.akine.knowledge.application.ColaDeConsultas;
import com.akine.knowledge.application.ConsultaService;
import com.akine.knowledge.application.LibroService;
import com.akine.knowledge.application.LibroService.ArchivoSubido;
import com.akine.knowledge.application.LibroService.DatosDeLibro;
import com.akine.knowledge.application.OperatingActor;
import com.akine.knowledge.application.ProcesamientoDeLibroService;
import com.akine.knowledge.domain.BibliotecaAjustes;
import com.akine.knowledge.domain.port.ModeloDeLenguaje;
import com.akine.knowledge.infrastructure.ModeloDeLenguajeDePrueba;
import com.akine.organization.application.MembershipService;
import com.akine.organization.spi.DirectMembershipCommand;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El endpoint de consulta de punta a punta: HTTP real contra el Tomcat del contexto, la cadena de
 * seguridad completa (login, contexto y permiso por el camino real, como {@code PacienteReadIT}),
 * MySQL, la busqueda con el modelo de embeddings de prueba y el modelo de lenguaje de prueba. El
 * stream se lee como lo lee el navegador: lineas {@code event:}/{@code data:}.
 *
 * <p>Pasar por el Tomcat real y no por MockMvc importa por dos cosas: el despacho ASYNC con que
 * termina el emisor atraviesa la cadena de seguridad, y la desconexion del cliente solo existe con
 * un socket de verdad.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"akine.security.rate-limit.enabled=false",
				"akine.archivos.base-dir=target/test-archivos",
				"akine.biblioteca.enabled=true",
				"akine.biblioteca.modelo.tipo=prueba",
				"akine.biblioteca.worker.enabled=false",
				"akine.biblioteca.consulta.enabled=true",
				"akine.biblioteca.consulta.tipo=prueba"})
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsultaIT {

	private static final String PASSWORD = "Sintetica-Akine-2026";
	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String SIN_MATERIAL = "No encontré material en la biblioteca para responder eso.";
	private static final String RELLENO = "La rehabilitacion de rodilla combina ejercicios de fuerza, "
			+ "movilidad y control motor progresivos segun la tolerancia del paciente.";

	@LocalServerPort int puerto;
	@Autowired JdbcTemplate jdbc;
	@Autowired LibroService libros;
	@Autowired ProcesamientoDeLibroService procesamiento;
	@Autowired MembershipService membershipService;
	@Autowired ColaDeConsultas cola;
	@Autowired ConsultaService consultaService;
	@Autowired ModeloDeLenguaje modeloDeLenguaje;

	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private ModeloDeLenguajeDePrueba modelo;
	private Sesion tenant;
	/** Texto de un pedazo del libro sembrado: preguntarlo da material seguro. */
	private String preguntaDelTema;

	@BeforeAll
	void sembrar() throws Exception {
		modelo = (ModeloDeLenguajeDePrueba) modeloDeLenguaje;
		tenant = altaCompleta("consulta-it");
		String palabra = palabra();
		long libro = libros.subir(new OperatingActor(tenant.cuentaId(), false, tenant.organizationId()),
				tenant.organizationId(), new DatosDeLibro("Libro " + UUID.randomUUID(), null, null, null),
				new ArchivoSubido("libro.pdf", pdf(pagina(palabra, 1), pagina(palabra, 2), pagina(palabra, 3))), true)
				.id();
		procesarHasta(libro);
		assertThat(jdbc.queryForObject("SELECT estado FROM libro WHERE id = ?", String.class, libro))
				.isEqualTo("LISTO");
		String texto = jdbc.queryForObject(
				"SELECT texto FROM libro_fragmento WHERE libro_id = ? ORDER BY orden LIMIT 1", String.class, libro);
		preguntaDelTema = texto.substring(0, Math.min(500, texto.length()));
	}

	@AfterEach
	void dejarLaColaLibre() throws InterruptedException {
		modelo.reiniciar();
		esperarHasta(() -> cola.enCurso() == 0 && cola.enEspera() == 0, Duration.ofSeconds(10));
		assertThat(cola.enCurso() + cola.enEspera()).as("la cola quedo libre").isZero();
	}

	// ------------------------------------------------------------------ escenarios

	@Test
	void respondeConFuentesYTexto() throws Exception {
		Stream respuesta = consultar(tenant.token(), cuerpo(preguntaDelTema));

		assertThat(respuesta.status()).as(respuesta.cuerpo()).isEqualTo(200);
		assertThat(respuesta.contentType()).startsWith("text/event-stream");
		assertThat(respuesta.sinBuffer()).isEqualTo("no");
		List<String> nombres = respuesta.nombres();
		assertThat(nombres.getFirst()).isEqualTo("fuentes");
		assertThat(nombres.getLast()).isEqualTo("fin");
		assertThat(nombres.subList(1, nombres.size() - 1)).isNotEmpty().containsOnly("texto");

		JsonNode fuentes = respuesta.eventos().getFirst().data().get("fuentes");
		assertThat(fuentes.size()).isBetween(1, 5);
		JsonNode fuente = fuentes.get(0);
		assertThat(fuente.get("libroId").asLong()).isPositive();
		assertThat(fuente.get("titulo").asString()).startsWith("Libro ");
		assertThat(fuente.get("paginaDesde").asInt()).isPositive();
		assertThat(fuente.get("extracto").asString()).isNotBlank().hasSizeLessThanOrEqualTo(300);

		JsonNode fin = respuesta.eventos().getLast().data();
		assertThat(fin.get("sinMaterial").asBoolean()).isFalse();
		assertThat(fin.get("camino").asString()).isEqualTo("VECTOR");
		assertThat(respuesta.textoCompleto()).startsWith("Respuesta de prueba: ");
	}

	@Test
	void sinMaterialNoLlamaAlModelo() throws Exception {
		int antes = modelo.llamadas();

		Stream respuesta = consultar(tenant.token(), cuerpo(palabra() + " " + palabra() + " " + palabra()));

		assertThat(respuesta.status()).as(respuesta.cuerpo()).isEqualTo(200);
		assertThat(respuesta.nombres()).containsExactly("texto", "fin");
		assertThat(respuesta.eventos().get(0).data().get("texto").asString()).isEqualTo(SIN_MATERIAL);
		assertThat(respuesta.eventos().get(1).data().get("sinMaterial").asBoolean()).isTrue();
		assertThat(modelo.llamadas()).isEqualTo(antes);
	}

	@Test
	void pacienteRecibe403() throws Exception {
		Sesion cuenta = altaCompleta("consulta-paciente");
		membershipService.createDirect(tenant.cuentaId(), false, tenant.organizationId(),
				new DirectMembershipCommand(cuenta.cuentaId(), tenant.consultorioId(), "PACIENTE", "prueba sintetica"));
		String token = seleccionarContexto(login(cuenta.email()), tenant.organizationId(), tenant.consultorioId());

		Stream respuesta = consultar(token, cuerpo(preguntaDelTema));

		assertThat(respuesta.status()).as(respuesta.cuerpo()).isEqualTo(403);
		assertThat(respuesta.contentType()).startsWith("application/problem+json");
		assertThat(respuesta.eventos()).isEmpty();
	}

	/** Las memberships de sede (R1): sin la sede del contexto en el permiso recibian 403. */
	@Test
	void profesionalDeUnaSedeConsulta() throws Exception {
		assertThat(consultarConRolDeSede("consulta-profesional", "PROFESIONAL").nombres()).startsWith("fuentes");
	}

	@Test
	void administradorDeSedeConsulta() throws Exception {
		assertThat(consultarConRolDeSede("consulta-admin-sede", "CONSULTORIO_ADMIN").nombres()).startsWith("fuentes");
	}

	private Stream consultarConRolDeSede(String etiqueta, String rol) throws Exception {
		Sesion cuenta = altaCompleta(etiqueta);
		membershipService.createDirect(tenant.cuentaId(), false, tenant.organizationId(),
				new DirectMembershipCommand(cuenta.cuentaId(), tenant.consultorioId(), rol, "prueba sintetica"));
		String token = seleccionarContexto(login(cuenta.email()), tenant.organizationId(), tenant.consultorioId());

		Stream respuesta = consultar(token, cuerpo(preguntaDelTema));

		assertThat(respuesta.status()).as(respuesta.cuerpo()).isEqualTo(200);
		return respuesta;
	}

	/**
	 * La consulta apagada con la biblioteca prendida. En vez de levantar un segundo contexto (otro
	 * Tomcat y otro esquema), se le cambia a {@code ConsultaService} el ajuste que lee
	 * {@code validar} durante este test y se restaura.
	 */
	@Test
	void apagadaRecibe409() throws Exception {
		Object servicio = AopTestUtils.getUltimateTargetObject(consultaService);
		BibliotecaAjustes original = (BibliotecaAjustes) ReflectionTestUtils.getField(servicio, "ajustes");
		ReflectionTestUtils.setField(servicio, "ajustes", new BibliotecaAjustes(
				original.habilitada(), original.maxBytes(), original.maxPaginas(), false));
		try {
			Stream respuesta = consultar(tenant.token(), cuerpo(preguntaDelTema));

			assertThat(respuesta.status()).as(respuesta.cuerpo()).isEqualTo(409);
			assertThat(respuesta.contentType()).startsWith("application/problem+json");
			assertThat(JSON.readTree(respuesta.cuerpo()).get("type").asString()).endsWith("/biblioteca-deshabilitada");
		} finally {
			ReflectionTestUtils.setField(servicio, "ajustes", original);
		}
	}

	@Test
	void desconexionLiberaLaCola() throws Exception {
		modelo.sinFin(true);
		HttpResponse<InputStream> respuesta = abrir(tenant.token(), cuerpo(preguntaDelTema));
		assertThat(respuesta.statusCode()).isEqualTo(200);
		try (BufferedReader lector = new BufferedReader(
				new InputStreamReader(respuesta.body(), StandardCharsets.UTF_8))) {
			Evento primero = siguienteEvento(lector);
			assertThat(primero.nombre()).isEqualTo("fuentes");
			assertThat(cola.enCurso()).isEqualTo(1);
		}

		esperarHasta(() -> cola.enCurso() == 0, Duration.ofSeconds(2));
		assertThat(cola.enCurso()).as("el cierre del cliente cancela la generacion y libera el lugar").isZero();
	}

	/**
	 * El cliente se va mientras el modelo todavia no emitio nada (Ollama evaluando el prompt): sin
	 * fragmentos, solo el latido revela el corte, y el lugar se libera sin esperar al primer token.
	 */
	@Test
	void desconexionAntesDelPrimerFragmentoLiberaLaCola() throws Exception {
		modelo.demorarAntesDelPrimero(Duration.ofSeconds(60));
		HttpResponse<InputStream> respuesta = abrir(tenant.token(), cuerpo(preguntaDelTema));
		assertThat(respuesta.statusCode()).isEqualTo(200);
		try (BufferedReader lector = new BufferedReader(
				new InputStreamReader(respuesta.body(), StandardCharsets.UTF_8))) {
			assertThat(siguienteEvento(lector).nombre()).isEqualTo("fuentes");
			assertThat(cola.enCurso()).isEqualTo(1);
		}

		esperarHasta(() -> cola.enCurso() == 0, Duration.ofSeconds(3));
		assertThat(cola.enCurso()).as("el latido nota el corte y libera el lugar antes del primer token").isZero();
	}

	@Test
	void modeloCaidoEmiteError() throws Exception {
		modelo.fallarEnGenerar(true);

		Stream respuesta = consultar(tenant.token(), cuerpo(preguntaDelTema));

		assertThat(respuesta.status()).as(respuesta.cuerpo()).isEqualTo(200);
		assertThat(respuesta.nombres()).containsExactly("fuentes", "error");
		assertThat(respuesta.eventos().getLast().data().get("type").asString()).endsWith("asistente-no-disponible");
	}

	/** Review focus 3: una corre, tres esperan en 1, 2 y 3, y la quinta es 503 sin abrir el stream. */
	@Test
	void colaLlenaRecibe503() throws Exception {
		modelo.sinFin(true);
		List<BufferedReader> abiertas = new ArrayList<>();
		try {
			BufferedReader enCurso = lector(abrir(tenant.token(), cuerpo(preguntaDelTema)));
			abiertas.add(enCurso);
			assertThat(siguienteEvento(enCurso).nombre()).isEqualTo("fuentes");
			for (int posicion = 1; posicion <= 3; posicion++) {
				BufferedReader enEspera = lector(abrir(tenant.token(), cuerpo(preguntaDelTema)));
				abiertas.add(enEspera);
				Evento espera = siguienteEvento(enEspera);
				assertThat(espera.nombre()).isEqualTo("espera");
				assertThat(espera.data().get("posicion").asInt()).isEqualTo(posicion);
			}
			assertThat(cola.enCurso()).isEqualTo(1);
			assertThat(cola.enEspera()).isEqualTo(3);

			Stream quinta = consultar(tenant.token(), cuerpo(preguntaDelTema));

			assertThat(quinta.status()).as(quinta.cuerpo()).isEqualTo(503);
			assertThat(quinta.contentType()).startsWith("application/problem+json");
			assertThat(quinta.retryAfter()).isEqualTo("15");
			assertThat(JSON.readTree(quinta.cuerpo()).get("type").asString()).endsWith("/consulta-ocupada");
		} finally {
			for (BufferedReader abierta : abiertas) {
				abierta.close();
			}
		}
	}

	// ------------------------------------------------------------------ stream

	record Evento(String nombre, JsonNode data) {
	}

	record Stream(int status, String contentType, String retryAfter, String sinBuffer, String cuerpo,
			List<Evento> eventos) {

		List<String> nombres() {
			return eventos.stream().map(Evento::nombre).toList();
		}

		String textoCompleto() {
			StringBuilder sb = new StringBuilder();
			eventos.stream().filter(e -> e.nombre().equals("texto"))
					.forEach(e -> sb.append(e.data().get("texto").asString()));
			return sb.toString();
		}
	}

	private static String cuerpo(String pregunta) {
		return JSON.writeValueAsString(java.util.Map.of("pregunta", pregunta, "historial", List.of()));
	}

	private HttpResponse<InputStream> abrir(String token, String cuerpo) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/organizations/" + tenant.organizationId()
						+ "/biblioteca/consultas"))
				.header("Content-Type", "application/json")
				.header("Accept", "text/event-stream")
				.header("Authorization", "Bearer " + token)
				.POST(HttpRequest.BodyPublishers.ofString(cuerpo))
				.build();
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()).get(30, TimeUnit.SECONDS);
	}

	/** Lee el stream entero (hasta que el servidor lo cierra), con tope de 30 s. */
	private Stream consultar(String token, String cuerpo) throws Exception {
		HttpResponse<InputStream> respuesta = abrir(token, cuerpo);
		String contentType = respuesta.headers().firstValue("Content-Type").orElse("");
		String retryAfter = respuesta.headers().firstValue("Retry-After").orElse(null);
		String sinBuffer = respuesta.headers().firstValue("X-Accel-Buffering").orElse(null);
		java.util.concurrent.CompletableFuture<String> lectura = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
			try (InputStream in = respuesta.body()) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8);
			} catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		});
		String cuerpoLeido = lectura.get(30, TimeUnit.SECONDS);
		List<Evento> eventos = new ArrayList<>();
		if (contentType.startsWith("text/event-stream")) {
			BufferedReader lector = new BufferedReader(new java.io.StringReader(cuerpoLeido));
			Evento evento;
			while ((evento = siguienteEvento(lector)) != null) {
				eventos.add(evento);
			}
		}
		return new Stream(respuesta.statusCode(), contentType, retryAfter, sinBuffer, cuerpoLeido, eventos);
	}

	private static BufferedReader lector(HttpResponse<InputStream> respuesta) {
		assertThat(respuesta.statusCode()).isEqualTo(200);
		return new BufferedReader(new InputStreamReader(respuesta.body(), StandardCharsets.UTF_8));
	}

	/** El proximo evento con nombre; saltea comentarios. {@code null} al final del stream. */
	private static Evento siguienteEvento(BufferedReader lector) throws IOException {
		String nombre = null;
		StringBuilder data = new StringBuilder();
		String linea;
		while ((linea = lector.readLine()) != null) {
			if (linea.isEmpty()) {
				if (nombre != null) {
					return new Evento(nombre, JSON.readTree(data.toString()));
				}
				data.setLength(0);
			} else if (linea.startsWith("event:")) {
				nombre = linea.substring("event:".length()).strip();
			} else if (linea.startsWith("data:")) {
				data.append(linea.substring("data:".length()));
			}
		}
		return nombre == null ? null : new Evento(nombre, JSON.readTree(data.toString()));
	}

	// ------------------------------------------------------------------ alta y sesion por HTTP

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

	// ------------------------------------------------------------------ libro

	private void procesarHasta(long id) {
		for (int i = 0; i < 200; i++) {
			String e = jdbc.queryForObject("SELECT estado FROM libro WHERE id = ?", String.class, id);
			if ("LISTO".equals(e) || "ERROR".equals(e) || !procesamiento.procesarSiguiente(Instant.now())) {
				return;
			}
		}
	}

	private static void esperarHasta(java.util.function.BooleanSupplier condicion, Duration tope)
			throws InterruptedException {
		long vence = System.nanoTime() + tope.toNanos();
		while (!condicion.getAsBoolean() && System.nanoTime() - vence < 0) {
			Thread.sleep(20);
		}
	}

	/** Diez letras al azar: no esta en ningun libro ni es palabra vacia de FULLTEXT. */
	private static String palabra() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			sb.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
		}
		return sb.toString();
	}

	private static String pagina(String palabra, int n) {
		StringBuilder sb = new StringBuilder("Capitulo " + n + ". " + palabra + ". ");
		while (sb.length() < 1300) {
			sb.append(RELLENO).append(' ');
		}
		return sb.toString();
	}

	private static byte[] pdf(String... paginas) throws IOException {
		try (PDDocument doc = new PDDocument()) {
			PDType1Font fuente = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			for (String texto : paginas) {
				PDPage page = new PDPage();
				doc.addPage(page);
				try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
					cs.beginText();
					cs.setFont(fuente, 9);
					cs.setLeading(11);
					cs.newLineAtOffset(40, 750);
					StringBuilder linea = new StringBuilder();
					for (String palabra : texto.split(" ")) {
						if (linea.length() + palabra.length() > 100) {
							cs.showText(linea.toString().strip());
							cs.newLine();
							linea.setLength(0);
						}
						linea.append(palabra).append(' ');
					}
					cs.showText(linea.toString().strip());
					cs.endText();
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}
}
