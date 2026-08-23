package com.akine.diferidos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenarios 1, 2 y 3 de {@code docs/tests-diferidos.md}.
 *
 * <p>Los tres se difirieron de AKINE-01.01 por la misma razon: sin login no habia forma de
 * tener dos sesiones reales de dos tenants distintos. Ahora la hay, y cada request de este
 * archivo viaja con un {@code Authorization: Bearer} emitido por {@code /auth/login} y acotado
 * por {@code /auth/context}.
 */
class AislamientoDeTenantIT extends BaseEscenarioDiferido {

	// =================================================================================
	// Escenario 1 — cross-tenant en todos los endpoints (CA-*-03, diseno sec.13 test 10)
	// =================================================================================

	@Test
	@DisplayName("1. Con contexto de A, los tres endpoints de B responden 404 y ninguno 403")
	void cross_tenant_responde_404_en_los_tres_endpoints() {
		Sesion a = altaCompleta("orga");
		Sesion b = altaCompleta("orgb");

		assertThat(a.organizationId()).isNotEqualTo(b.organizationId());

		Respuesta organizacion = get("/api/v1/organizations/" + b.organizationId(), a.token());
		Respuesta suscripcion =
				get("/api/v1/organizations/" + b.organizationId() + "/subscription", a.token());
		Respuesta consultorios =
				get("/api/v1/organizations/" + b.organizationId() + "/consultorios", a.token());

		assertThat(organizacion.status())
				.as("organizacion ajena: 404, nunca 403 —un 403 confirmaria que existe. %s",
						organizacion.body())
				.isEqualTo(404);
		assertThat(suscripcion.status())
				.as("suscripcion ajena: %s", suscripcion.body())
				.isEqualTo(404);
		assertThat(consultorios.status())
				.as("consultorios ajenos: %s", consultorios.body())
				.isEqualTo(404);

		assertProblemaLimpio(organizacion);
		assertProblemaLimpio(suscripcion);
		assertProblemaLimpio(consultorios);

		// Y el mismo token sigue funcionando sobre SU propia organizacion: el 404 de arriba es
		// aislamiento, no una sesion rota.
		assertThat(get("/api/v1/organizations/" + a.organizationId(), a.token()).status())
				.isEqualTo(200);
	}

	// =================================================================================
	// Escenario 2 — membership revocada entre requests (caso QA 6, test 11)
	// =================================================================================

	@Test
	@DisplayName("2. Revocada la membership, el request siguiente falla: ventana de revocacion cero")
	void la_membership_revocada_corta_el_request_siguiente() {
		Sesion sesion = altaCompleta("revocacion");

		// El contexto ya esta emitido y el token es criptograficamente valido.
		assertThat(get("/api/v1/organizations/" + sesion.organizationId(), sesion.token()).status())
				.as("antes de revocar, el contexto funciona")
				.isEqualTo(200);

		int revocadas = jdbc.update(
				"UPDATE membership SET active = 0, deleted_at = UTC_TIMESTAMP(6) "
						+ "WHERE organization_id = ? AND account_id = ?",
				sesion.organizationId(), sesion.cuentaId());
		assertThat(revocadas).isEqualTo(1);

		// Sin volver a loguearse, sin esperar a que venza el token: el request SIGUIENTE.
		Respuesta despues =
				get("/api/v1/organizations/" + sesion.organizationId(), sesion.token());

		assertThat(despues.status())
				.as("el token sigue firmado y vigente, pero la membership ya no existe: %s",
						despues.body())
				.isEqualTo(404);
		assertProblemaLimpio(despues);

		// Y tampoco puede volver a elegir ese contexto con la sesion que ya tenia abierta.
		Respuesta reeleccion = post("/api/v1/auth/context", sesion.tokenPreContexto(),
				"{\"organizationId\":" + sesion.organizationId()
						+ ",\"consultorioId\":" + sesion.consultorioId() + "}");
		assertThat(reeleccion.status()).isEqualTo(404);
	}

	// =================================================================================
	// Escenario 3 — suscripcion SUSPENDIDA (RN-M01-002, CA-*-06, test 12)
	// =================================================================================

	@Test
	@DisplayName("3. SUSPENDIDA: la mutacion es 409 subscription-suspended y el historico sigue en 200")
	void suspendida_bloquea_mutaciones_y_deja_leer() {
		Sesion sesion = altaCompleta("suspendida");

		Respuesta antes = get("/api/v1/organizations/" + sesion.organizationId(), sesion.token());
		assertThat(antes.status()).isEqualTo(200);
		long version = antes.json().get("version").asLong();

		int suspendidas = jdbc.update(
				"UPDATE subscription SET status = 'SUSPENDIDA', updated_at = UTC_TIMESTAMP(6) "
						+ "WHERE organization_id = ?",
				sesion.organizationId());
		assertThat(suspendidas).isEqualTo(1);

		// Mutacion de negocio: editar el tenant.
		Respuesta mutacion = patch("/api/v1/organizations/" + sesion.organizationId(), sesion.token(),
				"{\"name\":\"Centro Renombrado\",\"version\":" + version + "}");

		assertThat(mutacion.status())
				.as("suspender bloquea, jamas destruye: la mutacion es 409. %s", mutacion.body())
				.isEqualTo(409);
		assertThat(mutacion.texto("type"))
				.isEqualTo("https://akine.app/problems/subscription-suspended");
		assertProblemaLimpio(mutacion);

		// El nombre no se movio: el 409 corta antes de escribir.
		String nombre = jdbc.queryForObject(
				"SELECT name FROM organization WHERE id = ?", String.class, sesion.organizationId());
		assertThat(nombre).isNotEqualTo("Centro Renombrado");

		// Las lecturas siguen funcionando, incluida la del historico.
		Respuesta historico = get(
				"/api/v1/organizations/" + sesion.organizationId() + "/subscription/transitions",
				sesion.token());
		assertThat(historico.status())
				.as("el historico se sigue leyendo con la suscripcion suspendida: %s",
						historico.body())
				.isEqualTo(200);
		assertThat(historico.json().get("content").size())
				.as("el alta de la suscripcion dejo su fila inicial")
				.isGreaterThanOrEqualTo(1);

		assertThat(get("/api/v1/organizations/" + sesion.organizationId(), sesion.token()).status())
				.isEqualTo(200);
		assertThat(get("/api/v1/organizations/" + sesion.organizationId() + "/consultorios",
				sesion.token()).status()).isEqualTo(200);
	}
}
