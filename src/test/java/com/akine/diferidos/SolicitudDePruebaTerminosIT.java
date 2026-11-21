package com.akine.diferidos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El pedido de prueba de la landing exige aceptar los terminos vigentes (19/11/2026,
 * docs/diseno/AKINE-terminos-obligatorios.md).
 */
@TestPropertySource(properties = "akine.identity.terminos.version-vigente=" + SolicitudDePruebaTerminosIT.VERSION)
class SolicitudDePruebaTerminosIT extends BaseEscenarioDiferido {

	static final String VERSION = "2026-11-19";

	@Test
	@DisplayName("Sin aceptar los terminos: 400 con errors.aceptaTerminos y nada guardado")
	void sin_aceptar_es_400() {
		String email = email();

		Respuesta respuesta = pedir(email, null, null, null);

		assertThat(respuesta.status()).as(respuesta.body()).isEqualTo(400);
		assertThat(respuesta.texto("type")).endsWith("validation-error");
		assertThat(respuesta.json().get("errors").has("aceptaTerminos")).isTrue();
		assertThat(guardados(email)).isZero();
	}

	@Test
	@DisplayName("Con una version que no es la vigente: 400 con errors.versionTerminos")
	void version_vieja_es_400() {
		String email = email();

		Respuesta respuesta = pedir(email, true, "2025-01", null);

		assertThat(respuesta.status()).as(respuesta.body()).isEqualTo(400);
		assertThat(respuesta.json().get("errors").has("versionTerminos")).isTrue();
		assertProblemaLimpio(respuesta);
		assertThat(guardados(email)).isZero();
	}

	@Test
	@DisplayName("Aceptando la vigente: 202 y el pedido guarda la version y el instante")
	void aceptando_guarda_version_y_fecha() {
		String email = email();

		Respuesta respuesta = pedir(email, true, VERSION, null);

		assertThat(respuesta.status()).as(respuesta.body()).isEqualTo(202);
		Map<String, Object> fila = jdbc.queryForMap(
				"SELECT version_terminos, terminos_aceptados_en FROM solicitud_de_prueba WHERE email = ?",
				email);
		assertThat(fila.get("version_terminos")).isEqualTo(VERSION);
		assertThat(fila.get("terminos_aceptados_en")).isNotNull();
	}

	@Test
	@DisplayName("Con el campo trampa lleno sigue respondiendo 202 aunque no traiga terminos, sin guardar")
	void trampa_sigue_en_202() {
		String email = email();

		Respuesta respuesta = pedir(email, null, null, "https://spam.example.test");

		assertThat(respuesta.status()).as(respuesta.body()).isEqualTo(202);
		assertThat(guardados(email)).isZero();
	}

	private Respuesta pedir(String email, Boolean acepta, String version, String trampa) {
		StringBuilder cuerpo = new StringBuilder("{")
				.append("\"nombre\":\"Ana\",\"apellido\":\"Sintetica\",")
				.append("\"email\":\"").append(email).append("\",")
				.append("\"nombreCentro\":\"Estudio Sintetico\",")
				.append("\"kinesiologia\":true,\"actividades\":false,\"sedes\":\"UNA\",\"interesaIa\":false");
		if (trampa != null) {
			cuerpo.append(",\"sitioWeb\":\"").append(trampa).append("\"");
		}
		if (acepta != null) {
			cuerpo.append(",\"aceptaTerminos\":").append(acepta);
		}
		if (version != null) {
			cuerpo.append(",\"versionTerminos\":\"").append(version).append("\"");
		}
		return post("/api/v1/public/solicitudes-de-prueba", null, cuerpo.append("}").toString(),
				Map.of("Idempotency-Key", UUID.randomUUID().toString()));
	}

	private long guardados(String email) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM solicitud_de_prueba WHERE email = ?", Long.class, email);
	}

	private static String email() {
		return "prueba-terminos-" + UUID.randomUUID().toString().substring(0, 8) + "@centro.test";
	}
}
