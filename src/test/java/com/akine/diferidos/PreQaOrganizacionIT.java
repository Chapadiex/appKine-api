package com.akine.diferidos;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dos defectos del pre-QA del 14/10/2026 (`docs/qa/corridas/2026-10-14-pre-qa.md`), contra el
 * stack real y afirmando contra la base.
 *
 * <ul>
 *   <li><b>D3 (PLA-06).</b> El cambio de plan exige {@code expectedVersion}, y el
 *       {@code PLATFORM_ADMIN} no podia leer la suscripcion sin un acceso de soporte.</li>
 *   <li><b>D4 (ON-03).</b> {@code POST /auth/context} no escribia {@code account_active_context}:
 *       la tabla quedaba vacia para todas las cuentas.</li>
 * </ul>
 */
class PreQaOrganizacionIT extends BaseEscenarioDiferido {

	@Test
	@DisplayName("D3: la plataforma lee la version de la suscripcion sin soporte y cambia el plan "
			+ "con ella; el GET del centro sigue exigiendo soporte")
	void la_plataforma_lee_la_version_y_cambia_el_plan() {
		Sesion centro = altaCompleta("d3-centro");
		Sesion plataforma = altaCompleta("d3-plataforma");
		sembrarRolDePlataforma(plataforma.cuentaId());
		String ruta = "/api/v1/platform/organizations/" + centro.organizationId() + "/subscription";

		Respuesta lectura = get(ruta, plataforma.token());
		assertThat(lectura.status()).as(lectura.body()).isEqualTo(200);
		assertThat(lectura.texto("planCode")).isEqualTo("BASICO");
		assertThat(lectura.json().has("limits"))
				.as("el consumo de limites es dato del centro y no viaja").isFalse();
		long version = lectura.json().get("version").asLong();
		Long enLaBase = jdbc.queryForObject(
				"SELECT version FROM subscription WHERE organization_id = ?",
				Long.class, centro.organizationId());
		assertThat(version).isEqualTo(enLaBase);

		Respuesta cambio = post(
				"/api/v1/organizations/" + centro.organizationId() + "/subscription/plan-changes",
				plataforma.token(),
				"{\"planCode\":\"PROFESIONAL\",\"expectedVersion\":" + version + "}");
		assertThat(cambio.status()).as(cambio.body()).isEqualTo(200);
		assertThat(get(ruta, plataforma.token()).texto("planCode")).isEqualTo("PROFESIONAL");

		assertThat(get("/api/v1/organizations/" + centro.organizationId() + "/subscription",
				plataforma.token()).status())
				.as("los datos del centro siguen detras de tenant:read con soporte")
				.isEqualTo(403);
		assertThat(get(ruta, centro.token()).status())
				.as("la ruta de plataforma no es del ORG_ADMIN").isEqualTo(403);
	}

	@Test
	@DisplayName("D4: elegir contexto deja el puntero de la cuenta, y el proximo login lo marca "
			+ "como lastSelected")
	void elegir_contexto_deja_el_puntero() {
		Sesion sesion = altaCompleta("d4-contexto");

		Map<String, Object> puntero = jdbc.queryForMap(
				"SELECT organization_id, consultorio_id FROM account_active_context "
						+ "WHERE account_id = ?", sesion.cuentaId());
		assertThat(((Number) puntero.get("organization_id")).longValue())
				.isEqualTo(sesion.organizationId());
		assertThat(((Number) puntero.get("consultorio_id")).longValue())
				.isEqualTo(sesion.consultorioId());

		// Reelegir el mismo contexto no duplica la fila: el puntero es uno por cuenta.
		String otraVez = login(sesion.email());
		seleccionarContexto(otraVez, sesion.organizationId(), sesion.consultorioId());
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM account_active_context WHERE account_id = ?",
				Long.class, sesion.cuentaId())).isEqualTo(1L);

		Respuesta contextos = get("/api/v1/me/contexts", login(sesion.email()));
		assertThat(contextos.status()).isEqualTo(200);
		assertThat(contextos.json().get(0).get("lastSelected").asBoolean())
				.as("el ultimo contexto elegido se ofrece para preseleccionar").isTrue();
	}
}
