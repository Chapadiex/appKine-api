package com.akine.diferidos;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aislamiento por CONTEXTO, no por pertenencia: una misma cuenta administra dos organizaciones.
 *
 * <h2>Por que el escenario 1 de {@link AislamientoDeTenantIT} no alcanzaba</h2>
 *
 * <p>Ese escenario usa dos cuentas distintas, una por tenant. Ahi el aislamiento lo garantiza
 * el evaluador de permisos por si solo: la cuenta de A no tiene membership en B, y eso ya es
 * 404. Lo que no prueba es el caso de la cuenta que <b>si</b> es miembro de las dos —un
 * administrador que lleva dos centros— y trabaja con el contexto de A. El contexto activo es el
 * limite del request: lo que pide sobre B tiene que responderse como si B no existiera, aunque
 * la cuenta tenga permiso alla. Elegir el contexto de B es un acto explicito y auditado; un
 * {@code orgId} en la URL no puede reemplazarlo.
 *
 * <p>El E2E {@code cuenta-ciclo-real.spec.ts} del frontend lo encontro en
 * {@code GET /organizations/{orgId}/audit-events}, que devolvia 200 con la auditoria entera de
 * la otra organizacion. Las sedes y las memberships tenian el mismo defecto por la misma causa:
 * evaluaban el permiso contra la organizacion de la ruta sin compararla con la del contexto.
 */
class AislamientoPorContextoIT extends BaseEscenarioDiferido {

	@Test
	@DisplayName("Con contexto de A, ninguna ruta /organizations/{B}/... responde, aunque la cuenta administre B")
	void el_contexto_activo_acota_aunque_la_cuenta_administre_las_dos() {
		Sesion a = altaCompleta("ctx-a");
		Sesion b = altaCompleta("ctx-b");

		// La cuenta de A pasa a administrar tambien B, con alcance de organizacion entera.
		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, NULL, ?, 'ORG_ADMIN', 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", b.organizationId(), a.cuentaId());

		long membershipDelFundadorDeB = jdbc.queryForObject(
				"SELECT id FROM membership WHERE organization_id = ? AND account_id = ?",
				Long.class, b.organizationId(), b.cuentaId());

		// Sesion nueva de la cuenta de A, con el contexto de A elegido explicitamente.
		String preContexto = login(a.email());
		String tokenA = seleccionarContexto(preContexto, a.organizationId(), a.consultorioId());

		String orgB = "/api/v1/organizations/" + b.organizationId();
		String periodo = "?from=" + Instant.now().minus(1, ChronoUnit.DAYS)
				+ "&to=" + Instant.now().plus(1, ChronoUnit.DAYS);

		// Se juntan todas antes de fallar: el reporte tiene que nombrar CADA ruta que filtra, no
		// solo la primera.
		SoftAssertions ajenas = new SoftAssertions();

		// --- Las que ya aislaban: control ---
		assertNoExiste(ajenas, get(orgB, tokenA), "organizacion");
		assertNoExiste(ajenas, get(orgB + "/subscription", tokenA), "suscripcion");
		assertNoExiste(ajenas, get(orgB + "/consultorios", tokenA), "listado de sedes");
		assertNoExiste(ajenas, get(orgB + "/consultorios/" + b.consultorioId() + "/espacios", tokenA),
				"espacios de una sede");

		// --- Las que respondian 200 con datos de B ---
		assertNoExiste(ajenas, get(orgB + "/audit-events" + periodo, tokenA), "auditoria");
		assertNoExiste(ajenas, get(orgB + "/memberships", tokenA), "listado de colaboradores");
		assertNoExiste(ajenas, get(orgB + "/memberships/" + membershipDelFundadorDeB, tokenA),
				"un colaborador");
		assertNoExiste(ajenas, get(orgB + "/consultorios/" + b.consultorioId(), tokenA), "una sede");

		// Y una mutacion: suspender al fundador de B desde el contexto de A.
		assertNoExiste(ajenas, post(orgB + "/memberships/" + membershipDelFundadorDeB + "/suspend", tokenA,
				"{\"reason\":\"Sondeo de aislamiento desde otro contexto\"}"),
				"suspension de un colaborador");
		ajenas.assertAll();

		String estado = jdbc.queryForObject(
				"SELECT estado FROM membership WHERE id = ?", String.class, membershipDelFundadorDeB);
		assertThat(estado).as("el 404 corta antes de escribir").isEqualTo("ACTIVA");

		// --- Con el contexto de B, la misma cuenta lee lo de B: no es falta de permiso ---
		String tokenB = seleccionarContexto(preContexto, b.organizationId(), b.consultorioId());
		Respuesta auditoriaB = get(orgB + "/audit-events" + periodo, tokenB);
		assertThat(auditoriaB.status()).as("auditoria de B con contexto de B: %s", auditoriaB.body())
				.isEqualTo(200);
		assertThat(get(orgB + "/memberships", tokenB).status()).isEqualTo(200);
		assertThat(get(orgB + "/consultorios/" + b.consultorioId(), tokenB).status()).isEqualTo(200);

		// --- Y con el de A, lo de A sigue funcionando: el 404 es aislamiento, no sesion rota ---
		String orgA = "/api/v1/organizations/" + a.organizationId();
		assertThat(get(orgA + "/audit-events" + periodo, tokenA).status()).isEqualTo(200);
		assertThat(get(orgA + "/memberships", tokenA).status()).isEqualTo(200);
		assertThat(get(orgA + "/consultorios/" + a.consultorioId(), tokenA).status()).isEqualTo(200);
	}

	@Test
	@DisplayName("Sin contexto elegido, la auditoria, las memberships y las sedes responden 403")
	void sin_contexto_es_403() {
		Sesion a = altaCompleta("ctx-sin");
		String preContexto = a.tokenPreContexto();
		String orgA = "/api/v1/organizations/" + a.organizationId();
		String periodo = "?from=" + Instant.now().minus(1, ChronoUnit.DAYS)
				+ "&to=" + Instant.now().plus(1, ChronoUnit.DAYS);

		for (String ruta : new String[] {
				orgA + "/audit-events" + periodo,
				orgA + "/memberships",
				orgA + "/consultorios/" + a.consultorioId()}) {
			Respuesta respuesta = get(ruta, preContexto);
			assertThat(respuesta.status())
					.as("sin contexto %s: 403, nunca 401 ni 404. %s", ruta, respuesta.body())
					.isEqualTo(403);
			assertProblemaLimpio(respuesta);
		}
	}

	private static void assertNoExiste(SoftAssertions ajenas, Respuesta respuesta, String que) {
		ajenas.assertThat(respuesta.status())
				.as("%s de otra organizacion con el contexto de A: 404, nunca 200 ni 403. %s",
						que, respuesta.body())
				.isEqualTo(404);
		ajenas.assertThat(respuesta.body())
				.as("%s: ninguna respuesta de error puede filtrar internals", que)
				.doesNotContain("com.akine")
				.doesNotContain("org.springframework");
	}
}
