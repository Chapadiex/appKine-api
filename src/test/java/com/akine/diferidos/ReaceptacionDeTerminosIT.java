package com.akine.diferidos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reaceptacion de los terminos cuando cambia la version (19/11/2026,
 * docs/diseno/AKINE-terminos-obligatorios.md).
 *
 * <p>El cambio de version se simula dejando la aceptacion de la cuenta en una version vieja: es
 * exactamente lo que ve el backend al arrancar con una version nueva.
 */
@TestPropertySource(properties = "akine.identity.terminos.version-vigente=" + ReaceptacionDeTerminosIT.VERSION)
class ReaceptacionDeTerminosIT extends BaseEscenarioDiferido {

	static final String VERSION = "2026-11-19";

	@Test
	@DisplayName("Con la version vieja aceptada: entra, la API responde 403 terminos-pendientes y aceptar destraba")
	void reaceptacion_de_punta_a_punta() {
		String email = cuentaConVersionVieja();

		String token = login(email);

		Respuesta bloqueada = get("/api/v1/me", token);
		assertThat(bloqueada.status()).as(bloqueada.body()).isEqualTo(403);
		assertThat(bloqueada.texto("type")).endsWith("terminos-pendientes");
		assertThat(bloqueada.texto("versionVigente")).isEqualTo(VERSION);
		assertProblemaLimpio(bloqueada);

		Respuesta estado = get("/api/v1/me/terminos", token);
		assertThat(estado.status()).as(estado.body()).isEqualTo(200);
		assertThat(estado.json().get("pendiente").asBoolean()).isTrue();
		assertThat(estado.texto("versionVigente")).isEqualTo(VERSION);

		Respuesta otraVersion = post("/api/v1/me/terminos", token, "{\"versionTerminos\":\"2025-01\"}");
		assertThat(otraVersion.status()).as(otraVersion.body()).isEqualTo(400);
		assertThat(otraVersion.json().get("errors").has("versionTerminos")).isTrue();

		Respuesta aceptada = post("/api/v1/me/terminos", token, "{\"versionTerminos\":\"" + VERSION + "\"}");
		assertThat(aceptada.status()).as(aceptada.body()).isEqualTo(204);
		assertThat(jdbc.queryForObject("""
				SELECT a.origen FROM aceptacion_terminos a JOIN cuenta c ON c.id = a.cuenta_id
				 WHERE c.email_normalizado = ? AND a.version = ?
				""", String.class, normalizar(email), VERSION)).isEqualTo("REACEPTACION");

		Respuesta destrabada = get("/api/v1/me", token);
		assertThat(destrabada.status()).as(destrabada.body()).isEqualTo(200);
		assertThat(get("/api/v1/me/terminos", token).json().get("pendiente").asBoolean()).isFalse();

		Respuesta repetida = post("/api/v1/me/terminos", token, "{\"versionTerminos\":\"" + VERSION + "\"}");
		assertThat(repetida.status()).as("aceptar dos veces no falla").isEqualTo(204);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM aceptacion_terminos a JOIN cuenta c ON c.id = a.cuenta_id
				 WHERE c.email_normalizado = ? AND a.version = ?
				""", Long.class, normalizar(email), VERSION)).isEqualTo(1L);
	}

	@Test
	@DisplayName("Una cuenta pendiente puede iniciar sesion: el bloqueo no la deja sin salida")
	void la_sesion_no_se_bloquea() {
		String email = cuentaConVersionVieja();

		Respuesta login = post("/api/v1/auth/login", null,
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");

		assertThat(login.status()).as(login.body()).isEqualTo(200);
	}

	@Test
	@DisplayName("Los pedidos sin cuenta no pasan por el bloqueo")
	void publico_no_se_bloquea() {
		assertThat(get("/api/v1/terms", null).status()).isEqualTo(200);
	}

	@Test
	@DisplayName("Una cuenta que acepto la vigente al nacer opera sin pasar por la pantalla")
	void quien_ya_acepto_opera() {
		String email = cuentaNueva();
		activar(email);

		Respuesta me = get("/api/v1/me", login(email));

		assertThat(me.status()).as(me.body()).isEqualTo(200);
	}

	/** Cuenta activa cuya unica aceptacion es de una version anterior a la vigente. */
	private String cuentaConVersionVieja() {
		String email = cuentaNueva();
		activar(email);
		int filas = jdbc.update("""
				UPDATE aceptacion_terminos SET version = '2025-01'
				 WHERE cuenta_id = (SELECT id FROM cuenta WHERE email_normalizado = ?)
				""", normalizar(email));
		assertThat(filas).isEqualTo(1);
		return email;
	}

	private String cuentaNueva() {
		String email = "reacepta-" + UUID.randomUUID() + "@ejemplo.test";
		String base = cuerpoDeRegistro(email, "Reacepta", null, null);
		String cuerpo = base.substring(0, base.length() - 1)
				+ ",\"aceptaTerminos\":true,\"versionTerminos\":\"" + VERSION + "\"}";
		Respuesta alta = post("/api/v1/auth/register", null, cuerpo,
				Map.of("Idempotency-Key", UUID.randomUUID().toString()));
		assertThat(alta.status()).as(alta.body()).isEqualTo(202);
		return email;
	}
}
