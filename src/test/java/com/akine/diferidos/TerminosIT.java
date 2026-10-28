package com.akine.diferidos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DP-45 punto 5, mitad B: aceptacion de terminos en el alta, con una version vigente configurada.
 *
 * <p>Lo que mas importa es {@link #el_rechazo_no_distingue_si_el_email_existe()}: la exigencia
 * nueva no puede abrir el oraculo de cuentas que ADR-0018 cierra.
 */
@TestPropertySource(properties = {
		"akine.identity.terminos.version-vigente=" + TerminosIT.VERSION,
		"akine.identity.terminos.url=https://akine.example.test/terminos"})
class TerminosIT extends BaseEscenarioDiferido {

	static final String VERSION = "2026-10";

	@Test
	@DisplayName("GET /api/v1/terms publica la version vigente y la url, sin autenticar")
	void publica_la_version_vigente() {
		Respuesta respuesta = get("/api/v1/terms", null);

		assertThat(respuesta.status()).as(respuesta.body()).isEqualTo(200);
		assertThat(respuesta.texto("versionVigente")).isEqualTo(VERSION);
		assertThat(respuesta.texto("url")).isEqualTo("https://akine.example.test/terminos");
		assertThat(respuesta.json().get("aceptacionObligatoria").asBoolean()).isTrue();
	}

	@Test
	@DisplayName("Aceptando la version vigente: 202 y la aceptacion queda con la cuenta")
	void aceptando_queda_registrada() {
		String email = "terminos-ok-" + UUID.randomUUID() + "@ejemplo.test";

		Respuesta alta = registrarConTerminos(email, true, VERSION);

		assertThat(alta.status()).as(alta.body()).isEqualTo(202);
		Map<String, Object> aceptacion = jdbc.queryForMap("""
				SELECT a.version, a.origen, a.aceptado_en
				  FROM aceptacion_terminos a JOIN cuenta c ON c.id = a.cuenta_id
				 WHERE c.email_normalizado = ?
				""", normalizar(email));
		assertThat(aceptacion.get("version")).isEqualTo(VERSION);
		assertThat(aceptacion.get("origen")).isEqualTo("REGISTRO");
		assertThat(aceptacion.get("aceptado_en")).isNotNull();
	}

	@Test
	@DisplayName("Sin aceptar, o con otra version: 400 validation-error con el campo, y nada creado")
	void sin_aceptar_es_400_con_el_campo() {
		String email = "terminos-no-" + UUID.randomUUID() + "@ejemplo.test";

		Respuesta sinCampos = post("/api/v1/auth/register", null,
				cuerpoDeRegistro(email, "Terminos", null, null),
				Map.of("Idempotency-Key", UUID.randomUUID().toString()));
		assertThat(sinCampos.status()).as(sinCampos.body()).isEqualTo(400);
		assertThat(sinCampos.texto("type")).endsWith("validation-error");
		assertThat(sinCampos.json().get("errors").has("aceptaTerminos")).isTrue();

		Respuesta otraVersion = registrarConTerminos(email, true, "2025-01");
		assertThat(otraVersion.status()).as(otraVersion.body()).isEqualTo(400);
		assertThat(otraVersion.json().get("errors").has("versionTerminos")).isTrue();
		assertProblemaLimpio(otraVersion);

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM cuenta WHERE email_normalizado = ?", Long.class,
				normalizar(email))).isZero();
	}

	@Test
	@DisplayName("ADR-0018: el rechazo por terminos es el mismo con email libre y con email tomado")
	void el_rechazo_no_distingue_si_el_email_existe() {
		String emailTomado = "terminos-tomado-" + UUID.randomUUID() + "@ejemplo.test";
		assertThat(registrarConTerminos(emailTomado, true, VERSION).status()).isEqualTo(202);
		String emailLibre = "terminos-libre-" + UUID.randomUUID() + "@ejemplo.test";

		Respuesta conTomado = registrarConTerminos(emailTomado, false, VERSION);
		Respuesta conLibre = registrarConTerminos(emailLibre, false, VERSION);

		assertThat(conLibre.status()).isEqualTo(400).isEqualTo(conTomado.status());
		assertThat(conLibre.body()).as("byte a byte").isEqualTo(conTomado.body());

		// Y aceptando, el 202 tambien es el mismo; el duplicado no registra otra aceptacion.
		Respuesta okTomado = registrarConTerminos(emailTomado, true, VERSION);
		Respuesta okLibre = registrarConTerminos(emailLibre, true, VERSION);
		assertThat(okTomado.status()).isEqualTo(202);
		assertThat(okLibre.body()).isEqualTo(okTomado.body());
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM aceptacion_terminos a JOIN cuenta c ON c.id = a.cuenta_id
				 WHERE c.email_normalizado = ?
				""", Long.class, normalizar(emailTomado))).isEqualTo(1L);
	}

	@Test
	@DisplayName("Invitacion que crea la cuenta: exige los terminos y registra origen INVITACION")
	void la_invitacion_con_cuenta_nueva_exige_terminos() {
		Sesion centro = altaConTerminos("terminos-invita");
		String invitado = "invitado-" + UUID.randomUUID() + "@ejemplo.test";
		Respuesta emitida = post(
				"/api/v1/organizations/" + centro.organizationId() + "/colaborador-invitaciones",
				centro.token(),
				"{\"email\":\"%s\",\"roleCode\":\"PROFESIONAL\",\"consultorioId\":%d}"
						.formatted(invitado, centro.consultorioId()));
		assertThat(emitida.status()).as(emitida.body()).isEqualTo(201);

		// El token en claro solo viaja por correo: se fija uno conocido sobre la fila emitida.
		String token = "token-sintetico-" + UUID.randomUUID();
		jdbc.update("UPDATE colaborador_invitacion SET token_hash = SHA2(?, 256) WHERE id = ?",
				token, emitida.json().get("id").asLong());

		Respuesta sinTerminos = post("/api/v1/auth/invitations/accept", null,
				"{\"token\":\"%s\",\"nombre\":\"Invitada\",\"apellido\":\"DePrueba\",\"password\":\"%s\"}"
						.formatted(token, PASSWORD));
		assertThat(sinTerminos.status()).as(sinTerminos.body()).isEqualTo(400);
		assertThat(sinTerminos.json().get("errors").has("aceptaTerminos")).isTrue();

		Respuesta aceptada = post("/api/v1/auth/invitations/accept", null,
				("{\"token\":\"%s\",\"nombre\":\"Invitada\",\"apellido\":\"DePrueba\",\"password\":\"%s\","
						+ "\"aceptaTerminos\":true,\"versionTerminos\":\"%s\"}")
						.formatted(token, PASSWORD, VERSION));
		assertThat(aceptada.status()).as(aceptada.body()).isEqualTo(200);
		assertThat(jdbc.queryForObject("""
				SELECT a.origen FROM aceptacion_terminos a JOIN cuenta c ON c.id = a.cuenta_id
				 WHERE c.email_normalizado = ?
				""", String.class, normalizar(invitado))).isEqualTo("INVITACION");
	}

	// =================================================================================

	private Respuesta registrarConTerminos(String email, Boolean acepta, String version) {
		String base = cuerpoDeRegistro(email, "Terminos", null, null);
		String cuerpo = base.substring(0, base.length() - 1)
				+ ",\"aceptaTerminos\":" + acepta + ",\"versionTerminos\":\"" + version + "\"}";
		return post("/api/v1/auth/register", null, cuerpo,
				Map.of("Idempotency-Key", UUID.randomUUID().toString()));
	}

	private Sesion altaConTerminos(String etiqueta) {
		String email = etiqueta + "-" + UUID.randomUUID() + "@ejemplo.test";
		assertThat(registrarConTerminos(email, true, VERSION).status()).isEqualTo(202);
		activar(email);
		return abrirSesion(email);
	}
}
