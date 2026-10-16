package com.akine.diferidos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/platform/organizations} contra MySQL real: el listado de centros con el que
 * la consola de plataforma elige la suscripcion que opera (RF-M01-003, RNF-M01-005).
 *
 * <p>La base del IT la comparten otras clases, asi que cada escenario busca por una etiqueta
 * propia y aleatoria: los totales que se comprueban son los de sus centros, no los de la base.
 */
class PlataformaListaOrganizacionesIT extends BaseEscenarioDiferido {

	private static final String RUTA = "/api/v1/platform/organizations";

	@Test
	@DisplayName("La plataforma ve todos los centros, filtra por estado y recibe solo datos "
			+ "minimos; un ORG_ADMIN recibe 403")
	void la_plataforma_lista_y_el_org_admin_no() {
		String etiqueta = "platorg" + UUID.randomUUID().toString().substring(0, 8);
		Sesion centroA = altaCompleta(etiqueta + "-a");
		Sesion centroB = altaCompleta(etiqueta + "-b");
		Sesion plataforma = altaCompleta(etiqueta + "-plataforma");
		sembrarRolDePlataforma(plataforma.cuentaId());

		Respuesta todos = get(RUTA + "?q=" + etiqueta + "&size=50", plataforma.token());
		assertThat(todos.status()).as(todos.body()).isEqualTo(200);
		assertThat(todos.json().get("totalElements").asLong())
				.as("los tres centros de la etiqueta, de tres tenants distintos").isEqualTo(3);
		assertThat(ids(todos)).contains(
				centroA.organizationId(), centroB.organizationId(), plataforma.organizationId());

		JsonNode fila = todos.json().get("content").get(0);
		List<String> campos = new ArrayList<>(fila.propertyNames());
		assertThat(campos).as("datos minimos: nada de miembros, sedes, limites ni pacientes")
				.containsExactlyInAnyOrder(
						"id", "name", "slug", "status", "planCode", "planName", "createdAt");
		assertThat(fila.get("status").asString()).isEqualTo("ACTIVA");
		assertThat(fila.get("planCode").asString()).isEqualTo("BASICO");

		Respuesta suspension = post(
				"/api/v1/organizations/" + centroB.organizationId() + "/subscription/transitions",
				plataforma.token(),
				"{\"toStatus\":\"SUSPENDIDA\",\"expectedStatus\":\"ACTIVA\","
						+ "\"reason\":\"Falta de pago sintetica\"}");
		assertThat(suspension.status()).as(suspension.body()).isEqualTo(200);

		Respuesta suspendidos = get(
				RUTA + "?q=" + etiqueta + "&status=SUSPENDIDA", plataforma.token());
		assertThat(suspendidos.status()).as(suspendidos.body()).isEqualTo(200);
		assertThat(ids(suspendidos)).containsExactly(centroB.organizationId());

		Respuesta comodin = get(RUTA + "?q=%25" + etiqueta, plataforma.token());
		assertThat(comodin.json().get("totalElements").asLong())
				.as("un %% tipeado es literal, no un comodin").isZero();

		Respuesta orgAdmin = get(RUTA, centroA.token());
		assertThat(orgAdmin.status())
				.as("la ruta de plataforma no es del ORG_ADMIN: %s", orgAdmin.body())
				.isEqualTo(403);
		assertProblemaLimpio(orgAdmin);
	}

	private static List<Long> ids(Respuesta respuesta) {
		List<Long> ids = new ArrayList<>();
		respuesta.json().get("content").forEach(centro -> ids.add(centro.get("id").asLong()));
		return ids;
	}
}
