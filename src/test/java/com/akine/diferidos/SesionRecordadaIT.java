package com.akine.diferidos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DP-45 punto 5, mitad A: "mantener la sesion iniciada", contra MySQL real.
 *
 * <p>Con la configuracion por defecto (12 h la sesion comun, 30 d la recordada). Tambien cubre el
 * {@code GET /api/v1/terms} sin version configurada, que es el despliegue de hoy.
 */
class SesionRecordadaIT extends BaseEscenarioDiferido {

	private static final String ORIGEN = "http://localhost:4200";
	private static final Pattern MAX_AGE = Pattern.compile("Max-Age=(\\d+)");

	private static final long DOCE_HORAS = Duration.ofHours(12).toSeconds();
	private static final long TREINTA_DIAS = Duration.ofDays(30).toSeconds();

	@Test
	@DisplayName("Sin recordarme: la sesion y la cookie de siempre, 12 h absolutas")
	void sin_recordarme_es_la_sesion_de_siempre() {
		String email = cuentaActiva("sin-recordar");

		ConCookie login = login(email, null);

		assertThat(login.status()).as(login.body()).isEqualTo(200);
		assertThat(login.maxAge()).isBetween(DOCE_HORAS - 60, DOCE_HORAS);
		Map<String, Object> fila = ultimaFila(email);
		assertThat(fila.get("recordar_sesion")).isEqualTo(Boolean.FALSE);
		assertThat(vida(fila)).isEqualTo(Duration.ofHours(12));
	}

	@Test
	@DisplayName("Con recordarme: 30 d en la fila y en la cookie; la rotacion hereda marca y "
			+ "vencimiento; el logout la cierra igual")
	void con_recordarme_la_sesion_es_larga_y_la_rotacion_no_la_extiende() {
		String email = cuentaActiva("recordar");

		ConCookie login = login(email, true);
		assertThat(login.status()).as(login.body()).isEqualTo(200);
		assertThat(login.maxAge()).isBetween(TREINTA_DIAS - 60, TREINTA_DIAS);

		Map<String, Object> primera = ultimaFila(email);
		assertThat(primera.get("recordar_sesion")).isEqualTo(Boolean.TRUE);
		assertThat(vida(primera)).isEqualTo(Duration.ofDays(30));

		ConCookie rotada = refrescar(login.refresh());
		assertThat(rotada.status()).as(rotada.body()).isEqualTo(200);
		assertThat(rotada.maxAge())
				.as("la cookie rotada muere cuando muere la familia, no 30 d despues del canje")
				.isLessThanOrEqualTo(login.maxAge())
				.isGreaterThan(DOCE_HORAS);

		Map<String, Object> sucesora = ultimaFila(email);
		assertThat(sucesora.get("id")).isNotEqualTo(primera.get("id"));
		assertThat(sucesora.get("familia_id")).isEqualTo(primera.get("familia_id"));
		assertThat(sucesora.get("recordar_sesion")).isEqualTo(Boolean.TRUE);
		assertThat(sucesora.get("expira_en")).isEqualTo(primera.get("expira_en"));

		ConCookie logout = enviar(HttpRequest.newBuilder(uri("/api/v1/auth/logout"))
				.header("Origin", ORIGEN)
				.header("Cookie", "akine_rt=" + rotada.refresh())
				.POST(HttpRequest.BodyPublishers.noBody())
				.build());
		assertThat(logout.status()).isEqualTo(204);
		assertThat(refrescar(rotada.refresh()).status())
				.as("cerrada la sesion recordada, su refresh ya no canjea")
				.isEqualTo(401);
	}

	@Test
	@DisplayName("Con recordarme y credenciales malas: el mismo 401 de siempre")
	void con_recordarme_el_rechazo_es_el_mismo() {
		ConCookie rechazo = enviar(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
						"{\"email\":\"nadie-" + UUID.randomUUID() + "@ejemplo.test\","
								+ "\"password\":\"incorrecta-123456\",\"recordarme\":true}"))
				.build());

		assertThat(rechazo.status()).isEqualTo(401);
		assertThat(rechazo.body()).contains("invalid-credentials");
		assertThat(rechazo.setCookie()).noneMatch(valor -> valor.startsWith("akine_rt=")
				&& !valor.startsWith("akine_rt=;"));
	}

	@Test
	@DisplayName("GET /api/v1/terms sin version configurada: publico, sin exigencia")
	void terminos_sin_version_configurada() {
		Respuesta anonima = get("/api/v1/terms", null);
		assertThat(anonima.status()).as(anonima.body()).isEqualTo(200);
		assertThat(anonima.json().get("aceptacionObligatoria").asBoolean()).isFalse();
		// El null no viaja (Jackson NON_NULL): ausente o null, las dos cosas valen null en el cliente.
		assertThat(anonima.texto("versionVigente")).isNull();

		// Con un token pre_context tampoco lo corta el filtro de tenant.
		String preContexto = login(cuentaActiva("terminos-pre"));
		assertThat(get("/api/v1/terms", preContexto).status()).isEqualTo(200);
	}

	// =================================================================================

	private String cuentaActiva(String etiqueta) {
		String email = etiqueta + "-" + UUID.randomUUID() + "@ejemplo.test";
		assertThat(registrar(UUID.randomUUID().toString(), email, etiqueta, null, null).status())
				.isEqualTo(202);
		activar(email);
		return email;
	}

	private Map<String, Object> ultimaFila(String email) {
		return jdbc.queryForMap("""
				SELECT rt.id, rt.familia_id, rt.emitido_en, rt.expira_en, rt.recordar_sesion
				  FROM refresh_token rt JOIN cuenta c ON c.id = rt.cuenta_id
				 WHERE c.email_normalizado = ?
				 ORDER BY rt.id DESC LIMIT 1
				""", normalizar(email));
	}

	private static Duration vida(Map<String, Object> fila) {
		return Duration.between(
				(java.time.LocalDateTime) fila.get("emitido_en"),
				(java.time.LocalDateTime) fila.get("expira_en"));
	}

	private ConCookie login(String email, Boolean recordarme) {
		String extra = recordarme == null ? "" : ",\"recordarme\":" + recordarme;
		return enviar(HttpRequest.newBuilder(uri("/api/v1/auth/login"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(
						"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"" + extra + "}"))
				.build());
	}

	private ConCookie refrescar(String refresh) {
		return enviar(HttpRequest.newBuilder(uri("/api/v1/auth/refresh"))
				.header("Content-Type", "application/json")
				.header("Origin", ORIGEN)
				.header("Cookie", "akine_rt=" + refresh)
				.POST(HttpRequest.BodyPublishers.noBody())
				.build());
	}

	private ConCookie enviar(HttpRequest request) {
		try {
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			return new ConCookie(response.statusCode(), response.body(),
					response.headers().allValues("Set-Cookie"));
		} catch (java.io.IOException e) {
			throw new IllegalStateException("Fallo el request HTTP a " + request.uri(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Request interrumpido a " + request.uri(), e);
		}
	}

	private record ConCookie(int status, String body, List<String> setCookie) {

		private Optional<String> cookie() {
			return setCookie.stream().filter(valor -> valor.startsWith("akine_rt=")).findFirst();
		}

		String refresh() {
			String valor = cookie().orElseThrow().substring("akine_rt=".length());
			int fin = valor.indexOf(';');
			return fin < 0 ? valor : valor.substring(0, fin);
		}

		long maxAge() {
			Matcher matcher = MAX_AGE.matcher(cookie().orElseThrow());
			assertThat(matcher.find()).as("la cookie lleva Max-Age: %s", setCookie).isTrue();
			return Long.parseLong(matcher.group(1));
		}
	}
}
