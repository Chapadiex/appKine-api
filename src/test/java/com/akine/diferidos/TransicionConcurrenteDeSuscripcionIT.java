package com.akine.diferidos;

import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 4 de {@code docs/tests-diferidos.md} (RNF-M01-003, caso QA 2, test 13).
 *
 * <h2>Como se consigue un PLATFORM_ADMIN sin backdoor</h2>
 *
 * <p>{@code POST .../subscription/transitions} esta reservado a la administracion de
 * plataforma, y {@code AuthenticatedJwtPrincipal.platformAdmin()} sale del claim {@code rol},
 * que a su vez sale de la <b>membership revalidada</b> en la seleccion de contexto. Asi que el
 * rol se siembra donde el sistema lo lee: en la fila de {@code membership}, cuyo
 * {@code role_code} admite {@code PLATFORM_ADMIN} desde la migracion V3 y cuyo enum
 * {@code RoleCode} lo declara.
 *
 * <p>El token sigue saliendo de {@code /auth/login} mas {@code /auth/context}: nada se firma a
 * mano y el claim no lo propone el cliente. Es el mismo camino que va a formalizar 01.03
 * cuando defina de donde nace ese rol.
 *
 * <h2>Que encontro, y como quedo</h2>
 *
 * <p>El hilo perdedor recibia 500 y no 409: {@code SubscriptionService.transition} leia la
 * suscripcion sin bloqueo, el INSERT del historico tomaba un bloqueo compartido sobre la fila
 * padre por la FK, y al commitear las dos transacciones se pedian el exclusivo — deadlock de
 * InnoDB, {@code CannotAcquireLockException}, y el handler generico respondiendo un error de
 * servidor donde el contrato promete un conflicto. El arreglo es tomar el bloqueo exclusivo en
 * la primera lectura ({@code findByOrganizationIdForUpdate}): no hay escalada S -&gt; X posible
 * y la comparacion de {@code expectedStatus} pasa a decidirse sobre una fila bloqueada.
 */
class TransicionConcurrenteDeSuscripcionIT extends BaseEscenarioDiferido {

	// =================================================================================
	// El escenario tal como lo pide la tabla
	// =================================================================================

	@Test
	@DisplayName("4. Dos transiciones con el mismo expectedStatus: una gana y la otra recibe 409")
	void dos_transiciones_concurrentes_solo_una_gana() {
		Sesion objetivo = altaCompleta("objetivo-transicion");
		String tokenAdmin = tokenDeAdminDePlataforma();

		String ruta = "/api/v1/organizations/" + objetivo.organizationId()
				+ "/subscription/transitions";
		String cuerpo = "{\"toStatus\":\"SUSPENDIDA\",\"expectedStatus\":\"ACTIVA\","
				+ "\"reason\":\"Prueba sintetica de concurrencia\"}";

		long transicionesAntes = transicionesDe(objetivo.organizationId());

		Callable<Respuesta> intento = () -> post(ruta, tokenAdmin, cuerpo);
		List<Concurrencia.Resultado<Respuesta>> resultados =
				Concurrencia.enParalelo(List.of(intento, intento));

		List<Integer> estados = resultados.stream()
				.peek(r -> assertThat(r.fallo())
						.as("ninguno de los dos hilos debe morir por infraestructura: %s", r.error())
						.isFalse())
				.map(r -> r.valor().status())
				.sorted()
				.toList();

		assertThat(estados)
				.as("una gana con 200 y la otra recibe 409. Respuestas: %s",
						resultados.stream().map(r -> r.valor().body()).toList())
				.containsExactly(200, 409);

		assertProblemaLimpio(resultados.stream()
				.map(Concurrencia.Resultado::valor)
				.filter(r -> r.status() == 409)
				.findFirst()
				.orElseThrow());

		// El invariante que no depende del orden: la transicion se aplico UNA sola vez.
		assertThat(transicionesDe(objetivo.organizationId()))
				.as("el historico es append-only: exactamente una fila nueva, no dos")
				.isEqualTo(transicionesAntes + 1);
		assertThat(estadoDe(objetivo.organizationId())).isEqualTo("SUSPENDIDA");
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	/**
	 * Abre una sesion real cuya membership tiene {@code role_code = PLATFORM_ADMIN}.
	 *
	 * <p>El orden importa: primero se siembra el rol y RECIEN DESPUES se hace login y se elige
	 * contexto. Al reves, el token saldria con el rol viejo, porque el claim se resuelve en la
	 * seleccion de contexto y no en cada request.
	 */
	private String tokenDeAdminDePlataforma() {
		Sesion admin = altaCompleta("admin-plataforma");

		int filas = jdbc.update(
				"UPDATE membership SET role_code = 'PLATFORM_ADMIN' WHERE account_id = ?",
				admin.cuentaId());
		assertThat(filas).isEqualTo(1);

		return abrirSesion(admin.email()).token();
	}

	private long transicionesDe(long organizationId) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM subscription_transition WHERE organization_id = ?",
				Long.class, organizationId);
		return total == null ? 0L : total;
	}

	private String estadoDe(long organizationId) {
		return jdbc.queryForObject(
				"SELECT status FROM subscription WHERE organization_id = ?",
				String.class, organizationId);
	}
}
