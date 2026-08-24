package com.akine.diferidos;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;

import com.akine.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base de los tests de integracion que cierran la deuda de {@code docs/tests-diferidos.md}.
 *
 * <h2>Por que existe</h2>
 *
 * <p>Los once escenarios de esa tabla se difirieron con un motivo unico: <b>necesitan una
 * sesion autenticada real</b>. Con el login de AKINE-01.02 publicado, esta clase provee lo
 * unico que faltaba, y lo provee por el camino real: {@code POST /api/v1/auth/register},
 * {@code POST /api/v1/auth/login}, {@code GET /api/v1/me/contexts} y
 * {@code POST /api/v1/auth/context}, sobre HTTP y contra MySQL de verdad.
 *
 * <p><b>No hay backdoor de autenticacion y no puede haberlo.</b> No se inyecta principal, no
 * se activa ningun perfil que saltee la cadena de seguridad y no se firma ningun token a mano:
 * todo token que usan estos tests salio de {@code /auth/login} verificando un hash Argon2id
 * real. Lo unico que se siembra por SQL es el <b>estado</b> de la cuenta —de
 * PENDIENTE_ACTIVACION a ACTIVA—, porque el token de activacion solo existe en claro dentro
 * del proceso que lo genero (a la base va su SHA-256) y recuperarlo exigiria justamente el
 * atajo que estos tests existen para evitar. La autenticacion no se saltea: se ejerce.
 *
 * <h2>El unico ajuste de configuracion</h2>
 *
 * <p>{@code akine.security.rate-limit.enabled=false}. El limite es de 30 intentos por minuto
 * por ruta e IP; estos tests hacen mas de treinta logins desde 127.0.0.1 en menos de un minuto
 * y empezarian a recibir 429 en un orden que depende de la velocidad de la maquina. Apagarlo
 * no toca la autenticacion —el 401 por credencial invalida sigue igual—, solo la proteccion de
 * fuerza bruta, que no es lo que estos escenarios verifican y ya tiene sus propios tests en
 * {@code FixedWindowRateLimiterTest} y {@code RateLimitFilterTest}.
 */
// Perfil de desarrollo explicito: sin el, y sin AKINE_JWT_SECRET, la aplicacion no arranca.
// Es deliberado (ADR-0017) y lo fija ArranqueSinSecretoTest.
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "akine.security.rate-limit.enabled=false")
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
abstract class BaseEscenarioDiferido {

	/** Cumple la politica: mas de diez caracteres y fuera de la denylist. */
	protected static final String PASSWORD = "Sintetica-Akine-2026";

	protected static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort
	protected int puerto;

	@Autowired
	protected DataSource dataSource;

	protected JdbcTemplate jdbc;

	protected HttpClient http;

	@BeforeEach
	void prepararClienteYJdbc() {
		this.jdbc = new JdbcTemplate(dataSource);
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.build();
	}

	// =================================================================================
	// HTTP
	// =================================================================================

	/** Respuesta cruda: lo unico que estos tests necesitan es el codigo y el cuerpo. */
	protected record Respuesta(int status, String body) {

		JsonNode json() {
			return JSON.readTree(body);
		}

		String texto(String campo) {
			JsonNode valor = json().get(campo);
			return valor == null || valor.isNull() ? null : valor.asString();
		}
	}

	protected URI uri(String ruta) {
		return URI.create("http://localhost:" + puerto + ruta);
	}

	protected Respuesta get(String ruta, String token) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri(ruta)).GET();
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return ejecutar(builder.build());
	}

	protected Respuesta post(String ruta, String token, String cuerpo, Map<String, String> headers) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri(ruta))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(cuerpo == null ? "{}" : cuerpo));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		headers.forEach(builder::header);
		return ejecutar(builder.build());
	}

	protected Respuesta post(String ruta, String token, String cuerpo) {
		return post(ruta, token, cuerpo, Map.of());
	}

	protected Respuesta patch(String ruta, String token, String cuerpo) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri(ruta))
				.header("Content-Type", "application/json")
				.method("PATCH", HttpRequest.BodyPublishers.ofString(cuerpo));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		return ejecutar(builder.build());
	}

	protected Respuesta ejecutar(HttpRequest request) {
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			return new Respuesta(response.statusCode(), response.body());
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Fallo el request HTTP a " + request.uri(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Request interrumpido a " + request.uri(), e);
		}
	}

	// =================================================================================
	// Alta y sesion, por el camino real
	// =================================================================================

	/** Un tenant recien creado, con la sesion de su fundador ya acotada al contexto. */
	protected record Sesion(
			long cuentaId,
			String email,
			long organizationId,
			long consultorioId,
			String tokenPreContexto,
			String token) {
	}

	/**
	 * Da de alta cuenta + organizacion + consultorio + membership por
	 * {@code POST /api/v1/auth/register}, activa la cuenta, se loguea y elige contexto.
	 */
	protected Sesion altaCompleta(String etiqueta) {
		return altaCompleta(etiqueta, null, null);
	}

	protected Sesion altaCompleta(String etiqueta, String slug, String consultorioName) {
		String email = etiqueta + "-" + UUID.randomUUID() + "@ejemplo.test";
		Respuesta alta = registrar(
				UUID.randomUUID().toString(), email, etiqueta, slug, consultorioName);
		assertThat(alta.status())
				.as("el alta self-service responde 202 uniforme: %s", alta.body())
				.isEqualTo(202);

		activar(email);
		return abrirSesion(email);
	}

	/** Alta self-service cruda, para los escenarios que necesitan controlar la clave. */
	protected Respuesta registrar(
			String claveIdempotencia, String email, String organizacion,
			String slug, String consultorioName) {

		return post("/api/v1/auth/register", null,
				cuerpoDeRegistro(email, organizacion, slug, consultorioName),
				Map.of("Idempotency-Key", claveIdempotencia));
	}

	protected String cuerpoDeRegistro(
			String email, String organizacion, String slug, String consultorioName) {

		StringBuilder cuerpo = new StringBuilder("{")
				.append("\"email\":\"").append(email).append("\",")
				.append("\"password\":\"").append(PASSWORD).append("\",")
				.append("\"firstName\":\"Sintetica\",")
				.append("\"lastName\":\"DePrueba\",")
				.append("\"organizationName\":\"Centro ").append(organizacion).append("\"");
		if (slug != null) {
			cuerpo.append(",\"organizationSlug\":\"").append(slug).append("\"");
		}
		if (consultorioName != null) {
			cuerpo.append(",\"consultorioName\":\"").append(consultorioName).append("\"");
		}
		return cuerpo.append("}").toString();
	}

	/**
	 * Deja la cuenta ACTIVA. Es lo unico que se siembra: ver el JavaDoc de la clase.
	 */
	protected void activar(String email) {
		int filas = jdbc.update(
				"UPDATE cuenta SET estado = 'ACTIVA' WHERE email_normalizado = ?",
				normalizar(email));
		assertThat(filas).as("el alta tiene que haber creado la cuenta %s", email).isEqualTo(1);
	}

	protected static String normalizar(String email) {
		return email.strip().toLowerCase(Locale.ROOT);
	}

	/** Login real + listado de contextos real + seleccion de contexto real. */
	protected Sesion abrirSesion(String email) {
		String preContexto = login(email);

		Respuesta contextos = get("/api/v1/me/contexts", preContexto);
		assertThat(contextos.status()).isEqualTo(200);
		JsonNode primero = contextos.json().get(0);
		assertThat(primero).as("la cuenta %s tiene que tener al menos un contexto", email)
				.isNotNull();

		long organizationId = primero.get("organizationId").asLong();
		long consultorioId = primero.get("consultorioId").asLong();

		String token = seleccionarContexto(preContexto, organizationId, consultorioId);

		Long cuentaId = jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, normalizar(email));

		return new Sesion(cuentaId, email, organizationId, consultorioId, preContexto, token);
	}

	protected String login(String email) {
		Respuesta login = post("/api/v1/auth/login", null,
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
		assertThat(login.status()).as("login de %s: %s", email, login.body()).isEqualTo(200);
		assertThat(login.texto("scope")).isEqualTo("pre_context");
		return login.texto("accessToken");
	}

	protected String seleccionarContexto(
			String preContexto, long organizationId, long consultorioId) {

		Respuesta contexto = post("/api/v1/auth/context", preContexto,
				"{\"organizationId\":" + organizationId
						+ ",\"consultorioId\":" + consultorioId + "}");
		assertThat(contexto.status())
				.as("seleccion de contexto org=%s consultorio=%s: %s",
						organizationId, consultorioId, contexto.body())
				.isEqualTo(200);
		assertThat(contexto.texto("scope")).isEqualTo("context");
		return contexto.texto("accessToken");
	}

	// =================================================================================
	// Aserciones compartidas
	// =================================================================================

	/** Comprueba que un error no filtra internals (ADR-0005) ademas de su codigo. */
	protected void assertProblemaLimpio(Respuesta respuesta) {
		assertThat(respuesta.body())
				.as("ninguna respuesta de error puede filtrar internals")
				.doesNotContain("com.akine")
				.doesNotContain("org.springframework")
				.doesNotContain("stacktrace");
	}

	/**
	 * Le da a una cuenta el rol de plataforma, escribiendo la fila que el sistema realmente lee.
	 *
	 * <p>Desde AKINE-01.03 {@code PLATFORM_ADMIN} vive en {@code platform_role} (ADR-0020) y no
	 * en {@code membership.role_code}, que ademas tiene un {@code CHECK} que lo prohibe desde la
	 * migracion V11: la matriz seccion 1.3 dice que ese rol no tiene membership en ninguna
	 * organizacion.
	 *
	 * <p><b>No es un backdoor de autenticacion.</b> La cuenta se registro y se autentico por el
	 * camino real; lo unico que se siembra es el dato administrativo que en produccion entra por
	 * el seed de la migracion V15 o por {@code PlatformRoleService}. Ese servicio exige que
	 * quien otorga ya sea administrador de plataforma, asi que arrancar desde cero por HTTP es
	 * imposible por construccion — que es exactamente el punto del bootstrap.
	 *
	 * <p>El rol se revalida en CADA request, asi que sembrarlo antes o despues del login da el
	 * mismo resultado.
	 *
	 * <p><b>{@code valid_from} se siembra un minuto en el pasado, y no es cosmetico:</b> el
	 * reloj del contenedor de MySQL y el de la JVM del test no estan sincronizados al
	 * microsegundo. Con {@code UTC_TIMESTAMP(6)} exacto, la vigencia puede quedar unos
	 * milisegundos en el FUTURO respecto del instante con el que el evaluador la compara, y el
	 * request siguiente responde 403 de forma intermitente. Se detecto corriendo el escenario de
	 * bajas concurrentes de AKINE-02.01, donde fallaban dos de cinco repeticiones.
	 */
	protected void sembrarRolDePlataforma(long cuentaId) {
		int filas = jdbc.update("""
				INSERT INTO platform_role (account_id, role_code, granted_by_account_id, reason,
				                           valid_from, active, version, created_at, updated_at)
				VALUES (?, 'PLATFORM_ADMIN', NULL, 'Fixture sintetico de test de integracion',
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", cuentaId);
		assertThat(filas)
				.as("el rol de plataforma tiene que quedar sembrado para la cuenta %s", cuentaId)
				.isEqualTo(1);
	}

	protected long contarFilas(String tabla) {
		Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + tabla, Long.class);
		return total == null ? 0L : total;
	}
}
