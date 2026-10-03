package com.akine.clinical;

import com.akine.TestcontainersConfiguration;
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
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La API REST de la historia clinica por persona (C-2) por HTTP real y contra MySQL.
 *
 * <p>Escrito contra el CONTRATO de la partichela de A2.T1, sin leer el controller. Entra por el
 * camino real —registro, login y seleccion de contexto— igual que {@code BaseEscenarioDiferido}:
 * no hay backdoor de autenticacion. Lo unico que se siembra por SQL es lo que en produccion
 * llegaria por otras etapas: el paciente (persona + perfil) y el rol clinico de la membership.
 *
 * <p><b>Nunca ejecutado:</b> necesita Docker, Java 21 y el controller de A2. Ver "Estado actual"
 * de la partichela.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "akine.security.rate-limit.enabled=false")
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class HistoriaClinicaApiIT {

	private static final String PASSWORD = "Sintetica-Akine-2026";
	private static final String JUSTIFICACION = "X-Justificacion-Acceso";
	private static final String MOTIVO = "Prueba de integracion sintetica";
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

	// =================================================================================
	// Obtener o abrir
	// =================================================================================

	@Test
	@DisplayName("dos PUT a la misma persona devuelven la misma historia y dejan una sola fila")
	void dos_put_dejan_una_sola_historia() {
		// AC-1
		Tenant centro = crearTenant("idem");
		long personaId = crearPaciente(centro);

		Respuesta primero = put(base(personaId), centro.token(), null);
		Respuesta segundo = put(base(personaId), centro.token(), null);

		assertThat(primero.status()).as(primero.body()).isEqualTo(200);
		assertThat(segundo.status()).as(segundo.body()).isEqualTo(200);
		assertThat(segundo.json().get("id").asLong()).isEqualTo(primero.json().get("id").asLong());
		assertThat(historiasDe(personaId))
				.as("el unique uk_historia_clinica_persona_vigente, no el SELECT previo, es la garantia")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("ocho PUT concurrentes: todos 200, la misma historia, una sola fila")
	void put_concurrentes_no_duplican() throws Exception {
		// AC-1
		Tenant centro = crearTenant("conc");
		long personaId = crearPaciente(centro);
		int hilos = 8;

		ExecutorService pool = Executors.newFixedThreadPool(hilos);
		CountDownLatch largada = new CountDownLatch(1);
		List<Future<Respuesta>> futuros = new ArrayList<>();
		try {
			for (int i = 0; i < hilos; i++) {
				Callable<Respuesta> pedido = () -> {
					largada.await();
					return put(base(personaId), centro.token(), null);
				};
				futuros.add(pool.submit(pedido));
			}
			largada.countDown();

			long idEsperado = -1;
			for (Future<Respuesta> futuro : futuros) {
				Respuesta respuesta = futuro.get(60, TimeUnit.SECONDS);
				assertThat(respuesta.status())
						.as("la apertura concurrente se resuelve como idempotente, nunca 500: %s",
								respuesta.body())
						.isEqualTo(200);
				long id = respuesta.json().get("id").asLong();
				idEsperado = idEsperado == -1 ? id : idEsperado;
				assertThat(id).isEqualTo(idEsperado);
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(historiasDe(personaId)).isEqualTo(1);
	}

	// =================================================================================
	// Ver
	// =================================================================================

	@Test
	@DisplayName("GET sin historia abierta es 404")
	void get_sin_historia_es_404() {
		// AC-1
		Tenant centro = crearTenant("sinhc");
		long personaId = crearPaciente(centro);

		Respuesta respuesta = get(base(personaId), centro.token());

		assertThat(respuesta.status()).as(respuesta.body()).isEqualTo(404);
		assertThat(respuesta.body()).doesNotContain("com.akine").doesNotContain("stacktrace");
	}

	@Test
	@DisplayName("una persona de otra organizacion es 404 en las seis operaciones, nunca 403")
	void otra_organizacion_es_404() {
		// AC-1
		Tenant a = crearTenant("orga");
		Tenant b = crearTenant("orgb");
		long personaDeA = crearPaciente(a);
		assertThat(put(base(personaDeA), a.token(), null).status()).isEqualTo(200);

		assertThat(put(base(personaDeA), b.token(), null).status())
				.as("abrir sobre la persona de otro tenant").isEqualTo(404);
		assertThat(get(base(personaDeA), b.token()).status())
				.as("ver la historia de otro tenant").isEqualTo(404);
		assertThat(put(base(personaDeA) + "/resumen", b.token(),
				"{\"texto\":\"intruso\",\"expectedVersion\":0}").status())
				.as("escribir el resumen de otro tenant").isEqualTo(404);
		assertThat(get(base(personaDeA) + "/antecedentes", b.token()).status())
				.as("listar antecedentes de otro tenant").isEqualTo(404);
		assertThat(post(base(personaDeA) + "/antecedentes", b.token(),
				"{\"tipo\":\"ALERGIA\",\"descripcion\":\"intruso\"}").status())
				.as("registrar antecedente en otro tenant").isEqualTo(404);
		assertThat(post(base(personaDeA) + "/antecedentes/1/baja", b.token(),
				"{\"motivo\":\"intruso\"}").status())
				.as("dar de baja en otro tenant").isEqualTo(404);
	}

	// =================================================================================
	// Resumen
	// =================================================================================

	@Test
	@DisplayName("el resumen con la version vigente es 200, y con una desfasada es 409")
	void resumen_con_version_desfasada_es_409() {
		// AC-1
		Tenant centro = crearTenant("resumen");
		long personaId = crearPaciente(centro);
		JsonNode abierta = put(base(personaId), centro.token(), null).json();
		long version = abierta.get("version").asLong();

		Respuesta ok = put(base(personaId) + "/resumen", centro.token(),
				"{\"texto\":\"Lumbalgia cronica\",\"expectedVersion\":" + version + "}");
		assertThat(ok.status()).as(ok.body()).isEqualTo(200);
		assertThat(ok.json().get("resumen").asString()).isEqualTo("Lumbalgia cronica");

		Respuesta desfasada = put(base(personaId) + "/resumen", centro.token(),
				"{\"texto\":\"Pisa al anterior\",\"expectedVersion\":" + version + "}");
		assertThat(desfasada.status())
				.as("la version vieja no pisa en silencio lo que escribio el primero")
				.isEqualTo(409);
		assertThat(get(base(personaId), centro.token()).json().get("resumen").asString())
				.isEqualTo("Lumbalgia cronica");
	}

	// =================================================================================
	// Antecedentes
	// =================================================================================

	@Test
	@DisplayName("registrar es 201, listar filtra por tipo y vigencia, y la baja repetida es igual")
	void ciclo_de_antecedentes() {
		// AC-1
		Tenant centro = crearTenant("antec");
		long personaId = crearPaciente(centro);
		assertThat(put(base(personaId), centro.token(), null).status()).isEqualTo(200);
		String ruta = base(personaId) + "/antecedentes";

		Respuesta alergia = post(ruta, centro.token(),
				"{\"tipo\":\"ALERGIA\",\"descripcion\":\"Penicilina\"}");
		Respuesta cirugia = post(ruta, centro.token(),
				"{\"tipo\":\"QUIRURGICO\",\"descripcion\":\"Meniscectomia\"}");
		assertThat(alergia.status()).as(alergia.body()).isEqualTo(201);
		assertThat(cirugia.status()).as(cirugia.body()).isEqualTo(201);
		long alergiaId = alergia.json().get("id").asLong();

		assertThat(get(ruta, centro.token()).json().size()).as("sin filtros").isEqualTo(2);
		JsonNode soloAlergias = get(ruta + "?tipo=ALERGIA", centro.token()).json();
		assertThat(soloAlergias.size()).as("filtrado por tipo").isEqualTo(1);
		assertThat(soloAlergias.get(0).get("id").asLong()).isEqualTo(alergiaId);

		Respuesta baja = post(ruta + "/" + alergiaId + "/baja", centro.token(),
				"{\"motivo\":\"Era un error de carga\"}");
		Respuesta repetida = post(ruta + "/" + alergiaId + "/baja", centro.token(),
				"{\"motivo\":\"Otro motivo distinto\"}");

		assertThat(baja.status()).as(baja.body()).isEqualTo(200);
		assertThat(repetida.status()).as("la baja repetida no es un conflicto").isEqualTo(200);
		assertThat(repetida.json().get("deactivationReason").asString())
				.as("el motivo original queda intacto")
				.isEqualTo("Era un error de carga");

		assertThat(get(ruta + "?soloVigentes=true", centro.token()).json().size())
				.as("la dada de baja sale de los vigentes").isEqualTo(1);
		assertThat(get(ruta + "?soloVigentes=false", centro.token()).json().size())
				.as("pero sigue consultable").isEqualTo(2);
	}

	@Test
	@DisplayName("sin cabecera de justificacion el acceso es 403: hoy no hay relacion asistencial")
	void sin_justificacion_es_403() {
		// AC-1
		Tenant centro = crearTenant("justif");
		long personaId = crearPaciente(centro);

		Respuesta sin = put(base(personaId), centro.token(), null, null);
		Respuesta con = put(base(personaId), centro.token(), null, MOTIVO);

		assertThat(sin.status())
				.as("la cabecera es opcional en la firma pero AutorizacionClinica la exige: %s",
						sin.body())
				.isEqualTo(403);
		assertThat(con.status()).as(con.body()).isEqualTo(200);
		assertThat(historiasDe(personaId)).as("el rechazo no abrio nada, el pedido con motivo si")
				.isEqualTo(1);
	}

	// =================================================================================
	// Fixture: tenant, paciente y HTTP
	// =================================================================================

	private record Tenant(long organizationId, long consultorioId, long cuentaId, String token) {
	}

	private record Respuesta(int status, String body) {

		JsonNode json() {
			return JSON.readTree(body);
		}
	}

	private static String base(long personaId) {
		return "/api/v1/historias-clinicas/por-persona/" + personaId;
	}

	/**
	 * Alta por el camino real, y la membership del fundador pasada a PROFESIONAL.
	 *
	 * <p>El fundador nace ORG_ADMIN y {@code hc:write} no es suyo por base: la matriz lo deja como
	 * grant explicito. El PROFESIONAL si lo trae, asi que se cambia el rol de la fila y no se
	 * inventa un permiso.
	 */
	private Tenant crearTenant(String etiqueta) {
		String email = etiqueta + "-" + UUID.randomUUID() + "@ejemplo.test";
		Respuesta alta = post("/api/v1/auth/register", null,
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\","
						+ "\"firstName\":\"Sintetica\",\"lastName\":\"DePrueba\","
						+ "\"organizationName\":\"Centro " + etiqueta + "\"}",
				UUID.randomUUID().toString());
		assertThat(alta.status()).as("alta self-service: %s", alta.body()).isEqualTo(202);

		String normalizado = email.strip().toLowerCase(Locale.ROOT);
		jdbc.update("UPDATE cuenta SET estado = 'ACTIVA' WHERE email_normalizado = ?", normalizado);
		long cuentaId = jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, normalizado);
		jdbc.update("UPDATE membership SET role_code = 'PROFESIONAL' WHERE account_id = ?", cuentaId);

		Respuesta login = post("/api/v1/auth/login", null,
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
		assertThat(login.status()).as("login: %s", login.body()).isEqualTo(200);
		String preContexto = login.json().get("accessToken").asString();

		JsonNode contexto = get("/api/v1/me/contexts", preContexto).json().get(0);
		long organizationId = contexto.get("organizationId").asLong();
		long consultorioId = contexto.get("consultorioId").asLong();

		Respuesta sesion = post("/api/v1/auth/context", preContexto,
				"{\"organizationId\":" + organizationId + ",\"consultorioId\":" + consultorioId + "}");
		assertThat(sesion.status()).as("seleccion de contexto: %s", sesion.body()).isEqualTo(200);

		return new Tenant(organizationId, consultorioId, cuentaId,
				sesion.json().get("accessToken").asString());
	}

	/** Persona con perfil de paciente vigente, escrita por SQL como en {@code TimelineIT}. */
	private long crearPaciente(Tenant tenant) {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);
		jdbc.update("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave,
				                     nombre_clave, active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), "Paciente" + sufijo, ("PACIENTE" + sufijo).toUpperCase());
		long personaId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), personaId, tenant.cuentaId());
		return personaId;
	}

	private long historiasDe(long personaId) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM historia_clinica WHERE persona_id = ?", Long.class, personaId);
	}

	private Respuesta get(String ruta, String token) {
		return enviar(constructor(ruta, token, MOTIVO).GET().build());
	}

	private Respuesta put(String ruta, String token, String cuerpo) {
		return put(ruta, token, cuerpo, MOTIVO);
	}

	private Respuesta put(String ruta, String token, String cuerpo, String justificacion) {
		return enviar(constructor(ruta, token, justificacion)
				.PUT(HttpRequest.BodyPublishers.ofString(cuerpo == null ? "" : cuerpo)).build());
	}

	private Respuesta post(String ruta, String token, String cuerpo) {
		return enviar(constructor(ruta, token, MOTIVO)
				.POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build());
	}

	private Respuesta post(String ruta, String token, String cuerpo, String claveIdempotencia) {
		return enviar(constructor(ruta, token, null)
				.header("Idempotency-Key", claveIdempotencia)
				.POST(HttpRequest.BodyPublishers.ofString(cuerpo)).build());
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
