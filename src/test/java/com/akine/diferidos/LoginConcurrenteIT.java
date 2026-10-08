package com.akine.diferidos;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La misma cuenta abriendo sesion en varios dispositivos a la vez.
 *
 * <h2>El defecto que fija este test</h2>
 *
 * <p>Lo encontraron los E2E de E-2: de cinco logins simultaneos de la misma cuenta, con la
 * contrasena correcta, entraba UNO. Otro recibia {@code 409} y tres recibian {@code 500} por
 * deadlock. No es un caso de laboratorio: es cualquiera que abre AKINE en la notebook y en el
 * telefono, o dos pestanas que restauran sesion al mismo tiempo.
 *
 * <p>El login escribia {@code cuenta.ultimo_login_en} y {@code intentos_fallidos} a traves de la
 * entidad, que tiene {@code @Version}. Dos causas encimadas:
 *
 * <ol>
 *   <li><b>Deadlock por escalada S&rarr;X.</b> El {@code UPDATE cuenta} se difiere al flush del
 *       commit, pero el {@code INSERT refresh_token} sale en el acto (id {@code IDENTITY}) y su
 *       foreign key a {@code cuenta} toma un lock COMPARTIDO sobre la fila de la cuenta. Dos
 *       logins quedan con el S tomado, cada uno pide el X para su UPDATE y espera al otro.</li>
 *   <li><b>Optimistic lock sobre un dato que no lo necesita.</b> El que sobrevive al deadlock
 *       encuentra la version movida por el ganador y muere en {@code 409}.</li>
 * </ol>
 *
 * <h2>Y lo que destapo de paso</h2>
 *
 * <p>El caso de las contrasenas equivocadas simultaneas respondia los cinco {@code 401} y aun asi
 * fallaba: el contador quedaba en CERO. El rechazo es una {@code RuntimeException} y Spring
 * revertia la transaccion entera del login, asi que ni el contador de fallos ni el
 * {@code LOGIN_FALLIDO} de la auditoria se persistieron nunca —la alerta de actividad sospechosa
 * no podia dispararse—. Es el mismo agujero que {@code SessionService.refrescar} ya habia pagado
 * con el reuso del refresh, y se cierra igual: {@code noRollbackFor}.
 *
 * <h2>Sobre que se afirma</h2>
 *
 * <p>Que TODOS los logins respondan {@code 200}, que cada access token sirva de verdad contra un
 * endpoint autenticado, y que en la base queden tantas familias de refresh vivas como logins.
 * El refresh se prueba con la misma vara: N tokens DISTINTOS de la misma cuenta, canjeados a la
 * vez, tienen que dar N exitos —el canje del MISMO token es reuso y lo cubre
 * {@link RotacionDeRefreshConcurrenteIT}—.
 */
class LoginConcurrenteIT extends BaseEscenarioDiferido {

	private static final int DISPOSITIVOS = 5;

	/** El filtro de Origin exige un origen permitido sobre /auth/refresh (ADR-0017). */
	private static final String ORIGEN = "http://localhost:4200";

	@RepeatedTest(3)
	@DisplayName("cinco logins simultaneos de la misma cuenta entran los cinco")
	void logins_simultaneos_de_la_misma_cuenta_entran_todos() {
		String email = cuentaActiva("login-concurrente");
		long cuentaId = cuentaId(email);

		Callable<RespuestaConCookie> login = () -> loginConCookie(email);
		List<Concurrencia.Resultado<RespuestaConCookie>> resultados =
				Concurrencia.enParalelo(IntStream.range(0, DISPOSITIVOS)
						.mapToObj(i -> login).toList());

		assertThat(estados(resultados))
				.as("cada dispositivo tiene que poder entrar. Respuestas: %s", describir(resultados))
				.containsOnly(200)
				.hasSize(DISPOSITIVOS);

		// Un 200 con un token que no sirve no es un login. Cada access se prueba contra un
		// endpoint autenticado.
		for (Concurrencia.Resultado<RespuestaConCookie> resultado : resultados) {
			String access = JSON.readTree(resultado.valor().body()).get("accessToken").asString();
			assertThat(get("/api/v1/me/contexts", access).status())
					.as("el access token emitido en paralelo tiene que autenticar")
					.isEqualTo(200);
			assertThat(resultado.valor().cookieDeRefresh()).isPresent();
		}

		Long familiasVivas = jdbc.queryForObject(
				"SELECT COUNT(DISTINCT familia_id) FROM refresh_token "
						+ "WHERE cuenta_id = ? AND revocado_en IS NULL",
				Long.class, cuentaId);
		assertThat(familiasVivas)
				.as("una sesion independiente por dispositivo")
				.isEqualTo(DISPOSITIVOS);

		assertThat(jdbc.queryForObject(
				"SELECT intentos_fallidos FROM cuenta WHERE id = ?", Integer.class, cuentaId))
				.isZero();
		assertThat(jdbc.queryForObject(
				"SELECT ultimo_login_en IS NOT NULL FROM cuenta WHERE id = ?",
				Boolean.class, cuentaId))
				.as("el login sigue dejando su marca")
				.isTrue();
	}

	@RepeatedTest(3)
	@DisplayName("cinco contrasenas equivocadas simultaneas: cinco 401 identicos y cinco fallos contados")
	void fallos_simultaneos_responden_todos_401_y_se_cuentan_todos() {
		String email = cuentaActiva("fallo-concurrente");
		long cuentaId = cuentaId(email);

		Callable<RespuestaConCookie> intento = () -> enviar(HttpRequest.newBuilder(
						uri("/api/v1/auth/login"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
						"{\"email\":\"" + email + "\",\"password\":\"la-equivocada-2026\"}"))
				.build());
		List<Concurrencia.Resultado<RespuestaConCookie>> resultados =
				Concurrencia.enParalelo(IntStream.range(0, DISPOSITIVOS)
						.mapToObj(i -> intento).toList());

		// Un email inexistente responde siempre 401. Si una cuenta existente pudiera responder
		// 409 o 500 bajo concurrencia, el codigo diria que la cuenta existe (ADR-0018).
		assertThat(estados(resultados))
				.as("el rechazo tiene que ser uniforme. Respuestas: %s", describir(resultados))
				.containsOnly(401)
				.hasSize(DISPOSITIVOS);
		assertThat(jdbc.queryForObject(
				"SELECT intentos_fallidos FROM cuenta WHERE id = ?", Integer.class, cuentaId))
				.as("ningun fallo se pierde: el contador alimenta la alerta de actividad sospechosa")
				.isEqualTo(DISPOSITIVOS);
	}

	@RepeatedTest(3)
	@DisplayName("cinco refresh simultaneos de sesiones distintas de la misma cuenta renuevan los cinco")
	void refresh_simultaneos_de_sesiones_distintas_renuevan_todos() {
		String email = cuentaActiva("refresh-concurrente");

		List<String> refreshes = new ArrayList<>();
		for (int i = 0; i < DISPOSITIVOS; i++) {
			RespuestaConCookie login = loginConCookie(email);
			assertThat(login.status()).isEqualTo(200);
			refreshes.add(login.cookieDeRefresh().orElseThrow());
		}

		List<Callable<RespuestaConCookie>> canjes = refreshes.stream()
				.<Callable<RespuestaConCookie>>map(refresh -> () -> refrescar(refresh))
				.toList();
		List<Concurrencia.Resultado<RespuestaConCookie>> resultados =
				Concurrencia.enParalelo(canjes);

		assertThat(estados(resultados))
				.as("tokens distintos no son reuso: todos tienen que renovar. Respuestas: %s",
						describir(resultados))
				.containsOnly(200)
				.hasSize(DISPOSITIVOS);

		for (Concurrencia.Resultado<RespuestaConCookie> resultado : resultados) {
			JsonNode cuerpo = JSON.readTree(resultado.valor().body());
			assertThat(get("/api/v1/me/contexts", cuerpo.get("accessToken").asString()).status())
					.isEqualTo(200);
			// Y el sucesor tambien se puede canjear: la cadena sigue viva.
			assertThat(refrescar(resultado.valor().cookieDeRefresh().orElseThrow()).status())
					.isEqualTo(200);
		}
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private String cuentaActiva(String etiqueta) {
		String email = etiqueta + "-" + UUID.randomUUID() + "@ejemplo.test";
		assertThat(registrar(UUID.randomUUID().toString(), email, "Concurrente", null, null)
				.status()).isEqualTo(202);
		activar(email);
		return email;
	}

	private long cuentaId(String email) {
		return jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, normalizar(email));
	}

	private record RespuestaConCookie(int status, String body, List<String> setCookie) {

		Optional<String> cookieDeRefresh() {
			return setCookie.stream()
					.filter(valor -> valor.startsWith("akine_rt="))
					.map(valor -> valor.substring("akine_rt=".length()))
					.map(valor -> {
						int fin = valor.indexOf(';');
						return fin < 0 ? valor : valor.substring(0, fin);
					})
					.filter(valor -> !valor.isBlank())
					.findFirst();
		}
	}

	private RespuestaConCookie loginConCookie(String email) {
		return enviar(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
						"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
				.build());
	}

	private RespuestaConCookie refrescar(String refreshPlano) {
		return enviar(HttpRequest.newBuilder(uri("/api/v1/auth/refresh"))
				.header("Origin", ORIGEN)
				.header("Cookie", "akine_rt=" + refreshPlano)
				.POST(HttpRequest.BodyPublishers.noBody())
				.build());
	}

	private RespuestaConCookie enviar(HttpRequest request) {
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			return new RespuestaConCookie(
					response.statusCode(),
					response.body(),
					response.headers().allValues("Set-Cookie"));
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Fallo el request HTTP a " + request.uri(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Request interrumpido a " + request.uri(), e);
		}
	}

	private static List<Integer> estados(List<Concurrencia.Resultado<RespuestaConCookie>> resultados) {
		return resultados.stream().map(r -> r.fallo() ? -1 : r.valor().status()).toList();
	}

	private static String describir(List<Concurrencia.Resultado<RespuestaConCookie>> resultados) {
		return resultados.stream()
				.map(r -> r.fallo() ? "ERROR " + r.error() : r.valor().status() + " " + r.valor().body())
				.toList()
				.toString();
	}
}
