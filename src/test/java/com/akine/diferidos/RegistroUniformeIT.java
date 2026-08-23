package com.akine.diferidos;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0018 sobre {@code POST /api/v1/auth/register}: la respuesta tiene que ser indistinguible
 * exista o no la cuenta, <b>para cualquier combinacion de {@code planCode} y
 * {@code organizationSlug}</b>, validos o no.
 *
 * <h2>El agujero que fija este test</h2>
 *
 * <p>El registro tenia dos caminos: si el email estaba libre creaba la cuenta y llamaba al
 * {@code spi} de {@code organization}; si ya existia, no creaba nada y encolaba un aviso. Todo
 * lo que valida {@code organization} —plan contratable, slug libre— lo validaba SOLO el primer
 * camino. Con eso, un {@code planCode} inexistente respondia <b>404 con el email libre y 202
 * con el email tomado</b>: un unico request publico, sin autenticacion y sin depender de
 * tiempos, convertido en verificador de direcciones de correo.
 *
 * <p>Los tests de aca comparan las dos respuestas <b>byte a byte</b>, no solo el codigo: dos
 * cuerpos distintos con el mismo status distinguen igual.
 */
class RegistroUniformeIT extends BaseEscenarioDiferido {

	// =================================================================================
	// Tarea 1 — el oraculo por planCode y por organizationSlug
	// =================================================================================

	@Test
	@DisplayName("Un planCode inexistente responde lo mismo con email libre y con email tomado")
	void el_plan_inexistente_no_distingue_si_el_email_existe() {
		String emailTomado = registrarUnEmailQueYaExiste("plan-oraculo");
		String emailLibre = "plan-oraculo-libre-" + UUID.randomUUID() + "@ejemplo.test";
		String planInexistente = "PLAN-QUE-NO-EXISTE-" + UUID.randomUUID().toString().substring(0, 8);

		Respuesta conEmailTomado = registrarCon(emailTomado, planInexistente, null);
		Respuesta conEmailLibre = registrarCon(emailLibre, planInexistente, null);

		assertThat(conEmailLibre.status())
				.as("mismo status: libre=%s %s / tomado=%s %s",
						conEmailLibre.status(), conEmailLibre.body(),
						conEmailTomado.status(), conEmailTomado.body())
				.isEqualTo(conEmailTomado.status());
		assertThat(conEmailLibre.body())
				.as("y el mismo cuerpo, byte a byte")
				.isEqualTo(conEmailTomado.body());
		assertProblemaLimpio(conEmailLibre);

		// Y el rechazo no dejo rastro: el email libre sigue sin cuenta.
		assertThat(contarCuentas(emailLibre)).isZero();
	}

	@Test
	@DisplayName("Un planCode valido tambien responde lo mismo con email libre y con email tomado")
	void el_plan_valido_tampoco_distingue() {
		String emailTomado = registrarUnEmailQueYaExiste("plan-valido");
		String emailLibre = "plan-valido-libre-" + UUID.randomUUID() + "@ejemplo.test";

		Respuesta conEmailTomado = registrarCon(emailTomado, "BASICO", null);
		Respuesta conEmailLibre = registrarCon(emailLibre, "BASICO", null);

		// La otra mitad de la uniformidad: si solo se validara el plan cuando hace falta, la
		// rama valida y la invalida verian caminos distintos.
		assertThat(conEmailLibre.status()).isEqualTo(202);
		assertThat(conEmailTomado.status()).isEqualTo(202);
		assertThat(conEmailLibre.body()).isEqualTo(conEmailTomado.body());
	}

	@Test
	@DisplayName("Un slug ya tomado responde lo mismo con email libre y con email tomado")
	void el_slug_tomado_no_distingue_si_el_email_existe() {
		String slug = "slug-oraculo-" + UUID.randomUUID().toString().substring(0, 8);
		Sesion duena = altaCompleta("slugoraculo", slug, null);
		assertThat(duena.organizationId()).isPositive();

		String emailTomado = registrarUnEmailQueYaExiste("slug-oraculo");
		String emailLibre = "slug-oraculo-libre-" + UUID.randomUUID() + "@ejemplo.test";

		Respuesta conEmailTomado = registrarCon(emailTomado, null, slug);
		Respuesta conEmailLibre = registrarCon(emailLibre, null, slug);

		assertThat(conEmailLibre.status())
				.as("mismo status: libre=%s %s / tomado=%s %s",
						conEmailLibre.status(), conEmailLibre.body(),
						conEmailTomado.status(), conEmailTomado.body())
				.isEqualTo(conEmailTomado.status());
		assertThat(conEmailLibre.body()).isEqualTo(conEmailTomado.body());
		assertProblemaLimpio(conEmailLibre);
		assertThat(contarCuentas(emailLibre)).isZero();
	}

	// =================================================================================
	// El mismo patron, en otra pieza: el oraculo por firstName
	// =================================================================================

	@Test
	@DisplayName("Un firstName que el outbox rechaza responde lo mismo con email libre y tomado")
	void el_firstname_sospechoso_no_distingue_si_el_email_existe() {
		// EXACTAMENTE el bug de planCode, en otro lugar. La validacion del payload del outbox
		// —que rechaza valores con "password", "secret", "jwt", "token=", "://" o "bearer "—
		// vivia DENTRO de emitirActivacion, que solo corre cuando el email esta libre; la rama
		// del duplicado encola un payload vacio y nunca validaba. Con eso, "firstName":"Password"
		// respondia 400 con el email libre y 202 con el email tomado: el padron completo de
		// cuentas, un request por direccion, sin autenticacion y sin depender de tiempos.
		String emailTomado = registrarUnEmailQueYaExiste("firstname-oraculo");
		String emailLibre = "firstname-oraculo-libre-" + UUID.randomUUID() + "@ejemplo.test";

		Respuesta conEmailTomado = registrarConNombre(emailTomado, "Password");
		Respuesta conEmailLibre = registrarConNombre(emailLibre, "Password");

		assertThat(conEmailLibre.status())
				.as("mismo status: libre=%s %s / tomado=%s %s",
						conEmailLibre.status(), conEmailLibre.body(),
						conEmailTomado.status(), conEmailTomado.body())
				.isEqualTo(conEmailTomado.status());
		assertThat(conEmailLibre.body())
				.as("y el mismo cuerpo, byte a byte")
				.isEqualTo(conEmailTomado.body());
		assertProblemaLimpio(conEmailLibre);

		// El rechazo no dejo rastro: el email libre sigue sin cuenta, igual que en la otra rama.
		assertThat(contarCuentas(emailLibre)).isZero();
	}

	@Test
	@DisplayName("Un firstName normal tampoco distingue: los dos caminos dan el mismo 202")
	void el_firstname_normal_tampoco_distingue() {
		// La otra mitad de la uniformidad. Sin este caso, "siempre 400" tambien pasaria el test
		// de arriba y el endpoint quedaria roto para todo el mundo.
		String emailTomado = registrarUnEmailQueYaExiste("firstname-valido");
		String emailLibre = "firstname-valido-libre-" + UUID.randomUUID() + "@ejemplo.test";

		Respuesta conEmailTomado = registrarConNombre(emailTomado, "Sintetica");
		Respuesta conEmailLibre = registrarConNombre(emailLibre, "Sintetica");

		assertThat(conEmailLibre.status()).isEqualTo(202);
		assertThat(conEmailTomado.status()).isEqualTo(202);
		assertThat(conEmailLibre.body()).isEqualTo(conEmailTomado.body());
		assertThat(contarCuentas(emailLibre)).isEqualTo(1);
	}

	// =================================================================================
	// Tarea 2 — la sesion JPA rota del alta compuesta
	// =================================================================================

	@Test
	@DisplayName("Misma Idempotency-Key con emails distintos y en paralelo: 202 a los dos, nunca 500")
	void la_misma_clave_con_emails_distintos_no_da_500() {
		String clave = UUID.randomUUID().toString();

		// Emails distintos a proposito: con el mismo email corta antes el unique de cuenta y
		// nunca se llega a la colision de uk_onboarding_key, que es la que rompia la sesion de
		// JPA del lado de organization.
		Callable<Respuesta> uno = () -> registrar(
				clave, "clave-a-" + UUID.randomUUID() + "@ejemplo.test", "ClaveA", null, null);
		Callable<Respuesta> otro = () -> registrar(
				clave, "clave-b-" + UUID.randomUUID() + "@ejemplo.test", "ClaveB", null, null);

		List<Concurrencia.Resultado<Respuesta>> resultados =
				Concurrencia.enParalelo(List.of(uno, otro));

		assertThat(resultados.stream().map(r -> r.valor().status()).toList())
				.as("el 202 uniforme de ADR-0018 vale tambien bajo concurrencia. Respuestas: %s",
						describir(resultados))
				.containsExactly(202, 202);
		assertThat(resultados.stream().map(r -> r.valor().body()).distinct().count())
				.as("y con el mismo cuerpo: %s", describir(resultados))
				.isEqualTo(1);

		assertThat(contarOnboardingsDeOrganizacion(clave))
				.as("una sola alta compuesta para esa clave")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("Secuencial: la misma clave con otro email responde 202 y no crea un segundo tenant")
	void la_misma_clave_secuencial_no_crea_un_segundo_tenant() {
		String clave = UUID.randomUUID().toString();
		long organizacionesAntes = contarFilas("organization");

		Respuesta primera = registrar(
				clave, "clave-sec-a-" + UUID.randomUUID() + "@ejemplo.test", "SecA", null, null);
		Respuesta segunda = registrar(
				clave, "clave-sec-b-" + UUID.randomUUID() + "@ejemplo.test", "SecB", null, null);

		assertThat(primera.status()).isEqualTo(202);
		assertThat(segunda.status())
				.as("el replay por clave se acusa igual: %s", segunda.body())
				.isEqualTo(202);
		assertThat(contarFilas("organization"))
				.as("una sola organizacion para esa clave")
				.isEqualTo(organizacionesAntes + 1);
	}

	// =================================================================================
	// Tarea 4 — errores del cliente que respondian 500
	// =================================================================================

	@Test
	@DisplayName("Un JSON roto en el registro es 400, no 500")
	void json_roto_es_400() {
		Respuesta respuesta = post("/api/v1/auth/register", null,
				"{\"email\": \"roto@ejemplo.test\", ",
				Map.of("Idempotency-Key", UUID.randomUUID().toString()));

		assertThat(respuesta.status())
				.as("cuerpo: %s", respuesta.body())
				.isEqualTo(400);
		assertProblemaLimpio(respuesta);
	}

	@Test
	@DisplayName("Un tipo equivocado en el cuerpo es 400 y no refleja lo recibido")
	void tipo_equivocado_es_400() {
		Respuesta respuesta = post("/api/v1/auth/register", null,
				"{\"email\":{\"inyectado\":\"<script>\"},\"password\":\"" + PASSWORD + "\","
						+ "\"firstName\":\"A\",\"lastName\":\"B\",\"organizationName\":\"C\"}",
				Map.of("Idempotency-Key", UUID.randomUUID().toString()));

		assertThat(respuesta.status())
				.as("cuerpo: %s", respuesta.body())
				.isEqualTo(400);
		assertThat(respuesta.body()).doesNotContain("inyectado").doesNotContain("script");
		assertProblemaLimpio(respuesta);
	}

	@Test
	@DisplayName("Un Content-Type text/plain es 415, no 500")
	void content_type_no_soportado_es_415() {
		Respuesta respuesta = ejecutar(java.net.http.HttpRequest
				.newBuilder(uri("/api/v1/auth/register"))
				.header("Content-Type", "text/plain")
				.header("Idempotency-Key", UUID.randomUUID().toString())
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString("no soy json"))
				.build());

		assertThat(respuesta.status())
				.as("cuerpo: %s", respuesta.body())
				.isEqualTo(415);
		assertProblemaLimpio(respuesta);
	}

	// El 405 no se puede ejercitar contra un endpoint publico real: la cadena de seguridad
	// solo abre POST /api/v1/auth/register, asi que un DELETE sobre esa ruta lo rechaza el
	// filtro con 401 antes de que el DispatcherServlet decida que el metodo no existe. Es el
	// orden correcto —autenticar antes que enrutar— y el 405 queda cubierto en el slice
	// GlobalExceptionHandlerTest, que no tiene esa cadena delante.

	// =================================================================================
	// Apoyo
	// =================================================================================

	/** Deja una direccion con cuenta creada, por el camino real. */
	private String registrarUnEmailQueYaExiste(String etiqueta) {
		String email = etiqueta + "-tomado-" + UUID.randomUUID() + "@ejemplo.test";
		Respuesta alta = registrar(UUID.randomUUID().toString(), email, etiqueta, null, null);
		assertThat(alta.status()).isEqualTo(202);
		assertThat(contarCuentas(email)).isEqualTo(1);
		return email;
	}

	/** Alta con un {@code firstName} elegido: es lo unico que cambia entre las dos llamadas. */
	private Respuesta registrarConNombre(String email, String firstName) {
		String cuerpo = "{"
				+ "\"email\":\"" + email + "\","
				+ "\"password\":\"" + PASSWORD + "\","
				+ "\"firstName\":\"" + firstName + "\","
				+ "\"lastName\":\"DePrueba\","
				+ "\"organizationName\":\"Centro Uniforme\"}";

		return post("/api/v1/auth/register", null, cuerpo,
				Map.of("Idempotency-Key", UUID.randomUUID().toString()));
	}

	private Respuesta registrarCon(String email, String planCode, String slug) {
		StringBuilder cuerpo = new StringBuilder("{")
				.append("\"email\":\"").append(email).append("\",")
				.append("\"password\":\"").append(PASSWORD).append("\",")
				.append("\"firstName\":\"Sintetica\",")
				.append("\"lastName\":\"DePrueba\",")
				.append("\"organizationName\":\"Centro Uniforme\"");
		if (planCode != null) {
			cuerpo.append(",\"planCode\":\"").append(planCode).append("\"");
		}
		if (slug != null) {
			cuerpo.append(",\"organizationSlug\":\"").append(slug).append("\"");
		}
		cuerpo.append("}");

		return post("/api/v1/auth/register", null, cuerpo.toString(),
				Map.of("Idempotency-Key", UUID.randomUUID().toString()));
	}

	private long contarCuentas(String email) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM cuenta WHERE email_normalizado = ?",
				Long.class, normalizar(email));
		return total == null ? 0L : total;
	}

	private long contarOnboardingsDeOrganizacion(String clave) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM organization_onboarding WHERE idempotency_key = ?",
				Long.class, clave);
		return total == null ? 0L : total;
	}

	private static String describir(List<Concurrencia.Resultado<Respuesta>> resultados) {
		return resultados.stream()
				.map(r -> r.fallo() ? "ERROR " + r.error() : r.valor().status() + " " + r.valor().body())
				.toList()
				.toString();
	}
}
