package com.akine.diferidos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/** DP-45 punto 3: {@code GET /api/v1/me}, contra el stack real. */
class PerfilPropioIT extends BaseEscenarioDiferido {

	private static final String ME = "/api/v1/me";

	@Test
	@DisplayName("Con contexto trae cuenta, organizacion, sede y rol; sin datos sensibles")
	void con_contexto() {
		Sesion s = altaCompleta("dp45-me-ctx");

		Respuesta r = get(ME, s.token());
		assertThat(r.status()).as(r.body()).isEqualTo(200);
		JsonNode json = r.json();
		assertThat(json.get("accountId").asLong()).isEqualTo(s.cuentaId());
		assertThat(json.get("email").asString()).isEqualTo(s.email().toLowerCase());
		assertThat(json.get("firstName").asString()).isEqualTo("Sintetica");
		assertThat(json.get("lastName").asString()).isEqualTo("DePrueba");

		JsonNode ctx = json.get("context");
		assertThat(ctx).as(r.body()).isNotNull();
		assertThat(ctx.get("organizationId").asLong()).isEqualTo(s.organizationId());
		assertThat(ctx.get("consultorioId").asLong()).isEqualTo(s.consultorioId());
		assertThat(ctx.get("organizationName").asString())
				.isEqualTo(jdbc.queryForObject(
						"SELECT name FROM organization WHERE id = ?", String.class, s.organizationId()));
		assertThat(ctx.get("consultorioName").asString()).isNotBlank();
		assertThat(ctx.get("roleCode").asString()).isEqualTo("ORG_ADMIN");
		assertThat(ctx.get("roleLabel").asString()).isNotBlank();

		assertThat(r.body()).doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("hash")
				.doesNotContainIgnoringCase("token");
	}

	@Test
	@DisplayName("Sin contexto (pre_context) responde 200 con context null")
	void sin_contexto() {
		Sesion s = altaCompleta("dp45-me-sin");

		Respuesta r = get(ME, s.tokenPreContexto());
		assertThat(r.status()).as(r.body()).isEqualTo(200);
		assertThat(r.json().get("accountId").asLong()).isEqualTo(s.cuentaId());
		assertThat(r.json().has("context")).as(r.body()).isFalse();
	}

	@Test
	@DisplayName("Sin sesion es 401 y cada cuenta ve solo la suya")
	void sin_sesion_y_nunca_otra_cuenta() {
		Sesion a = altaCompleta("dp45-me-a");
		Sesion b = altaCompleta("dp45-me-b");

		assertThat(get(ME, null).status()).isEqualTo(401);

		JsonNode deA = get(ME, a.token()).json();
		JsonNode deB = get(ME, b.token()).json();
		assertThat(deA.get("accountId").asLong()).isEqualTo(a.cuentaId());
		assertThat(deB.get("accountId").asLong()).isEqualTo(b.cuentaId());
		assertThat(deA.get("email").asString()).isNotEqualTo(deB.get("email").asString());
		assertThat(deA.get("context").get("organizationId").asLong())
				.isNotEqualTo(deB.get("context").get("organizationId").asLong());

		// Un parametro que intente cambiar de cuenta no tiene efecto.
		assertThat(get(ME + "?accountId=" + b.cuentaId(), a.token()).json().get("accountId").asLong())
				.isEqualTo(a.cuentaId());
	}
}
