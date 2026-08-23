package com.akine.diferidos;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akine.organization.domain.exception.PlanLimitExceededException;
import com.akine.organization.spi.LimitCode;
import com.akine.organization.spi.PlanGate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenario 8 de {@code docs/tests-diferidos.md} (challenge B-3). <b>El mas importante de los
 * once</b>, segun la nota al pie de ese documento. Y encontro lo que fue a buscar.
 *
 * <h2>Que prueba</h2>
 *
 * <p>El plan BASICO permite cinco miembros activos. Con cuatro ya dados de alta, dos hilos
 * intentan a la vez el quinto: uno tiene que entrar y el otro tiene que recibir
 * {@code PlanLimitExceededException}. <b>Nunca los dos.</b>
 *
 * <p>Es el unico test que distingue el diseno correcto del incorrecto. Ningun unique cubre el
 * caso: un unique restringe valores repetidos, no una cantidad. Lo unico que puede cerrarlo es
 * serializar por tenant antes de contar.
 *
 * <h2>Por que el test invoca el gate y no un endpoint</h2>
 *
 * <p>Porque hoy no existe ningun endpoint que de de alta un recurso limitado: {@code PlanGate}
 * no tiene ningun consumidor en {@code src/main} —la primera alta de consultorio llega en
 * 02.01—. El test ocupa el lugar de ese consumidor futuro y hace exactamente lo que el
 * contrato del {@code spi} le exige: entregarle al gate el contador y la creacion, y dejar que
 * el gate abra la transaccion, bloquee, cuente, decida y commitee.
 *
 * <h2>Que encontro, y como quedo</h2>
 *
 * <p>Este test fallaba: entraban los dos hilos y la organizacion terminaba con SEIS miembros
 * activos y un tope de cinco, sin error y sin auditoria de rechazo. La causa no era el
 * protocolo del gate sino la isolation: en REPEATABLE READ el conteo posterior al bloqueo lee
 * de un snapshot anterior al commit del otro hilo. El arreglo vive en
 * {@code PlanGate.createWithinLimit}, que abre la transaccion del alta con READ COMMITTED. El
 * test ya no elige isolation: la elige el gate, que es de quien depende que este bien.
 */
class LimiteDePlanConcurrenteIT extends BaseEscenarioDiferido {

	/** Tope de {@code MAX_MIEMBROS_ACTIVOS} del plan BASICO, sembrado por la migracion V4. */
	private static final int TOPE_BASICO = 5;

	private static final AtomicLong SECUENCIA = new AtomicLong();

	@Autowired
	private PlanGate planGate;

	// =================================================================================
	// El escenario tal como lo pide la tabla, contra la configuracion REAL de la aplicacion
	// =================================================================================

	@Test
	@DisplayName("8. Dos altas simultaneas del recurso numero limite: una crea, la otra 409. Nunca dos")
	void dos_altas_simultaneas_del_recurso_limite_solo_una_entra() {
		correrLaCarrera();
	}

	// =================================================================================
	// La carrera
	// =================================================================================

	private void correrLaCarrera() {
		Sesion sesion = altaCompleta("limite-plan");
		long organizationId = sesion.organizationId();

		assertThat(planDe(organizationId))
				.as("el alta self-service contrata el plan por defecto, que es el que tiene tope")
				.isEqualTo("BASICO");

		// El fundador ya cuenta como miembro. Se completa hasta dejar el cupo en uno.
		while (miembrosActivos(organizationId) < TOPE_BASICO - 1) {
			insertarMembership(organizationId, cuentaSintetica());
		}
		assertThat(miembrosActivos(organizationId)).isEqualTo(TOPE_BASICO - 1);

		List<Concurrencia.Resultado<Long>> resultados = Concurrencia.enParalelo(List.of(
				altaConGate(organizationId, cuentaSintetica()),
				altaConGate(organizationId, cuentaSintetica())));

		long exitos = resultados.stream().filter(r -> !r.fallo()).count();
		long rechazos = resultados.stream()
				.filter(Concurrencia.Resultado::fallo)
				.filter(r -> causaEs(r.error(), PlanLimitExceededException.class))
				.count();

		assertThat(exitos)
				.as("exactamente un alta entra. Desenlaces: %s", describir(resultados))
				.isEqualTo(1);
		assertThat(rechazos)
				.as("la otra recibe PlanLimitExceededException (409). Desenlaces: %s",
						describir(resultados))
				.isEqualTo(1);

		// La afirmacion que no depende del orden en que corrieron los hilos, y que es la razon
		// de ser del test.
		assertThat(miembrosActivos(organizationId))
				.as("NUNCA dos: el tope del plan es %s", TOPE_BASICO)
				.isEqualTo(TOPE_BASICO);
	}

	/**
	 * Simula al modulo consumidor tal como el {@code spi} le pide que sea.
	 *
	 * <p><b>El test no abre la transaccion ni elige la isolation.</b> Las dos cosas las decide
	 * el gate, que es de quien depende que el limite se respete: si el consumidor pudiera
	 * elegirlas, cada modulo nuevo tendria que acordarse de esta trampa, y el dia que uno se
	 * olvide el limite se viola sin que nadie se entere.
	 *
	 * <p>El contador se pasa como {@link LongSupplier} y consulta la base de verdad: el gate lo
	 * invoca dentro de la transaccion y recien despues del bloqueo.
	 */
	private Callable<Long> altaConGate(long organizationId, long accountId) {
		return () -> planGate.createWithinLimit(
				organizationId,
				LimitCode.MAX_MIEMBROS_ACTIVOS,
				() -> miembrosActivos(organizationId),
				() -> {
					insertarMembership(organizationId, accountId);
					return accountId;
				});
	}

	// =================================================================================
	// Utilidades de datos, todas sinteticas
	// =================================================================================

	private void insertarMembership(long organizationId, long accountId) {
		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, active, version,
				                        created_at, updated_at)
				VALUES (?, NULL, ?, 'ADMINISTRATIVO', 0, UTC_TIMESTAMP(6), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, accountId);
	}

	/**
	 * Un identificador de cuenta que no colisiona con ninguno real.
	 *
	 * <p>{@code membership.account_id} es una referencia LOGICA a {@code identity}, sin FK
	 * fisica por ownership de modulo (migracion V3), asi que no hace falta crear cuentas de
	 * verdad para llenar el cupo. El rango esta muy por encima del autoincrement para no
	 * pisarse con ninguna cuenta creada por otro test del mismo contexto.
	 */
	private long cuentaSintetica() {
		return 900_000_000L + SECUENCIA.incrementAndGet();
	}

	private long miembrosActivos(long organizationId) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM membership WHERE organization_id = ? AND active = 1",
				Long.class, organizationId);
		return total == null ? 0L : total;
	}

	private String planDe(long organizationId) {
		return jdbc.queryForObject(
				"SELECT p.code FROM subscription s JOIN plan p ON p.id = s.plan_id "
						+ "WHERE s.organization_id = ?",
				String.class, organizationId);
	}

	private static boolean causaEs(Throwable error, Class<? extends Throwable> tipo) {
		for (Throwable actual = error; actual != null; actual = actual.getCause()) {
			if (tipo.isInstance(actual)) {
				return true;
			}
		}
		return false;
	}

	private static String describir(List<Concurrencia.Resultado<Long>> resultados) {
		return resultados.stream()
				.map(r -> r.fallo() ? "ERROR " + r.error() : "OK " + r.valor())
				.toList()
				.toString();
	}
}
