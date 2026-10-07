package com.akine.person.application;

import com.akine.person.domain.Autorizacion;
import com.akine.person.domain.AutorizacionMovimiento;

import java.time.LocalDate;
import java.util.List;

/**
 * Cuanto queda en una autorizacion, y si las dos fuentes coinciden (RF-M17-003).
 *
 * <h2>Devuelve el saldo DOS veces, y es el punto de esta consulta</h2>
 *
 * <p>{@code saldo} sale de la columna materializada {@code cantidad_consumida} —la que la
 * elegibilidad de 03.06 lee— y {@code saldoSegunElLedger} se recalcula sumando los movimientos.
 * <b>Tienen que dar lo mismo.</b> {@code coherente} dice si dan.
 *
 * <p>Es la unica mitigacion que esta etapa pudo poner al limite que el challenge dejo declarado:
 * si el {@code UPDATE} del saldo y el {@code INSERT} del movimiento divergieran alguna vez, nada
 * lo detectaria, porque no hay nadie recalculando la columna desde el ledger. Ahora hay algo —una
 * consulta que se puede mirar—, aunque el test de integracion que lo verifique automaticamente
 * siga sin poder escribirse: necesita Docker.
 *
 * <p><b>No corrige.</b> Si divergen, esta consulta lo dice y nada mas. Reescribir la columna desde
 * el ledger en una lectura seria una mutacion escondida en un GET, y ademas taparia el sintoma
 * antes de que nadie pueda averiguar que camino produjo la divergencia.
 *
 * @param cantidadAutorizada lo que el financiador otorgo. {@code null} = sin tope declarado
 * @param saldo              lo que queda segun la columna. {@code null} si no hay tope
 * @param saldoSegunElLedger lo mismo, recalculado desde los movimientos. {@code null} sin tope
 * @param coherente          las dos cuentas coinciden
 * @param habilita           el veredicto completo: activa, APROBADA, vigente y con saldo
 * @param consumosARevisar   alertas "consumo a revisar" pendientes (DP-13, AKINE C-4)
 */
public record SaldoDeAutorizacionView(
		long autorizacionId,
		long personaId,
		LocalDate fecha,
		Integer cantidadAutorizada,
		int cantidadConsumida,
		int consumidaSegunElLedger,
		Integer saldo,
		Integer saldoSegunElLedger,
		boolean coherente,
		boolean vigente,
		boolean vencida,
		boolean agotada,
		boolean habilita,
		Long diasParaVencer,
		int movimientos,
		int consumosARevisar) {

	public static SaldoDeAutorizacionView de(
			Autorizacion autorizacion, List<AutorizacionMovimiento> ledger, LocalDate fecha,
			int consumosARevisar) {

		// El signo lo da el tipo, nunca el numero: ver TipoMovimientoAutorizacion. La suma de los
		// efectos es negativa cuando se gasto, asi que lo consumido es su opuesto.
		int consumidaSegunElLedger = -ledger.stream()
				.mapToInt(AutorizacionMovimiento::efectoSobreElSaldo)
				.sum();

		Integer autorizada = autorizacion.getCantidadAutorizada();
		Integer saldoDelLedger = autorizada == null ? null : autorizada - consumidaSegunElLedger;

		return new SaldoDeAutorizacionView(
				autorizacion.getId(),
				autorizacion.getPersonaId(),
				fecha,
				autorizada,
				autorizacion.getCantidadConsumida(),
				consumidaSegunElLedger,
				autorizacion.saldo(),
				saldoDelLedger,
				autorizacion.getCantidadConsumida() == consumidaSegunElLedger,
				autorizacion.vigencia().cubre(fecha),
				autorizacion.vencidaEl(fecha),
				autorizacion.agotada(),
				autorizacion.habilitaEl(fecha),
				autorizacion.diasParaVencer(fecha),
				ledger.size(),
				consumosARevisar);
	}
}
