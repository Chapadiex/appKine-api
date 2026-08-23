package com.akine.diferidos;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La rotacion del refresh <b>serializada</b>: dos canjes simultaneos del mismo token, uno gana y
 * el otro es reuso.
 *
 * <h2>El agujero que fija este test</h2>
 *
 * <p>{@code SessionService.refrescar} lee la fila, comprueba que {@code usadoEn} sea nulo, emite
 * un sucesor y <i>recien entonces</i> marca la rotacion. Sin lock, sin optimistic locking y sin
 * ningun unique que lo impida, dos {@code POST /auth/refresh} en paralelo con la misma cookie
 * veian los dos el {@code usadoEn} nulo, insertaban los dos un sucesor en la misma familia y
 * devolvian los dos un par valido.
 *
 * <p>El efecto no es un duplicado inocente: <b>la familia queda bifurcada</b>. Atacante y victima
 * terminan con cadenas de rotacion independientes que nunca se cruzan, asi que la premisa central
 * de ADR-0017 —"su uso genera reuso y mata la familia entera"— deja de cumplirse. El robo de la
 * cookie se vuelve persistente y silencioso hasta las 12 h absolutas. Y es una carrera que el
 * atacante controla: elige cuando dispararla.
 *
 * <h2>Sobre que se afirma</h2>
 *
 * <p>No sobre el orden en que respondieron los hilos —eso no es determinista— sino sobre el
 * <b>estado final</b>, que si lo es: exactamente un canje exitoso, y <b>ni un solo refresh vivo
 * en la familia</b> despues de la carrera. Ese invariante da la misma respuesta hayan corrido en
 * paralelo o no, y no puede dar verde con el bloqueo removido: sin lock quedarian dos exitos y
 * dos eslabones vivos.
 *
 * <p>Se repite varias veces a proposito: la concurrencia no se puede forzar de forma absoluta
 * desde el proceso de test, asi que una sola corrida verde no dice mucho. {@link Concurrencia}
 * pone una barrera justo antes de la operacion para que la ventana entre los dos hilos sea de
 * nanosegundos.
 */
class RotacionDeRefreshConcurrenteIT extends BaseEscenarioDiferido {

	/** El filtro de Origin exige un origen permitido sobre /auth/refresh (ADR-0017). */
	private static final String ORIGEN = "http://localhost:4200";

	@RepeatedTest(5)
	@DisplayName("dos canjes simultaneos del mismo refresh: uno gana, el otro es reuso y mata la familia")
	void dos_canjes_simultaneos_dejan_la_familia_muerta() {
		String email = "rotacion-" + UUID.randomUUID() + "@ejemplo.test";
		assertThat(registrar(UUID.randomUUID().toString(), email, "Rotacion", null, null).status())
				.isEqualTo(202);
		activar(email);

		RespuestaConCookie login = loginConCookie(email);
		assertThat(login.status()).isEqualTo(200);
		String refresh = login.cookieDeRefresh().orElseThrow(
				() -> new AssertionError("el login tiene que instalar la cookie akine_rt"));

		long cuentaId = jdbc.queryForObject(
				"SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, normalizar(email));
		String familia = jdbc.queryForObject(
				"SELECT familia_id FROM refresh_token WHERE cuenta_id = ?", String.class, cuentaId);

		Callable<RespuestaConCookie> canje = () -> refrescar(refresh);
		List<Concurrencia.Resultado<RespuestaConCookie>> resultados =
				Concurrencia.enParalelo(List.of(canje, canje));

		List<Integer> estados = resultados.stream()
				.map(r -> r.fallo() ? -1 : r.valor().status())
				.toList();

		assertThat(estados)
				.as("un solo canje puede tener exito. Respuestas: %s", describir(resultados))
				.containsOnlyOnce(200);
		assertThat(estados)
				.as("y el otro tiene que ser rechazado como cualquier refresh invalido (401). "
						+ "Respuestas: %s", describir(resultados))
				.containsOnlyOnce(401);

		// El invariante duro: despues de la carrera no queda NINGUN eslabon canjeable. El
		// perdedor detecto el reuso y revoco la familia completa, sucesor del ganador incluido.
		Long vivos = jdbc.queryForObject(
				"SELECT COUNT(*) FROM refresh_token WHERE familia_id = ? AND revocado_en IS NULL",
				Long.class, familia);
		assertThat(vivos)
				.as("la deteccion de reuso revoca la familia entera: victima y atacante afuera")
				.isZero();

		Long conMotivoDeReuso = jdbc.queryForObject(
				"SELECT COUNT(*) FROM refresh_token "
						+ "WHERE familia_id = ? AND motivo_revocacion = 'ROTACION_REUSO'",
				Long.class, familia);
		assertThat(conMotivoDeReuso)
				.as("y lo hace por el motivo correcto, que es lo que despues se audita")
				.isPositive();

		// Y la sesion queda efectivamente cerrada: reintentar con la cookie original no revive.
		assertThat(refrescar(refresh).status()).isEqualTo(401);
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	/** Respuesta que ademas conserva la cookie de refresh, que la clase base no expone. */
	protected record RespuestaConCookie(int status, String body, List<String> setCookie) {

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
				.header("Content-Type", "application/json")
				// Sin Origin el filtro de CSRF corta con 403 antes de llegar al servicio.
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

	private static String describir(List<Concurrencia.Resultado<RespuestaConCookie>> resultados) {
		return resultados.stream()
				.map(r -> r.fallo() ? "ERROR " + r.error() : r.valor().status() + " " + r.valor().body())
				.toList()
				.toString();
	}
}
