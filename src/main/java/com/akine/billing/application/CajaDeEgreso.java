package com.akine.billing.application;

import com.akine.billing.domain.JornadaCaja;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.MovimientoCaja;
import com.akine.billing.domain.OrigenMovimiento;
import com.akine.billing.domain.TipoMovimiento;
import com.akine.billing.domain.exception.CajaNoAbiertaException;
import com.akine.billing.domain.exception.CajaSaldoInsuficienteException;
import com.akine.billing.domain.port.JornadaCajaRepositoryPort;
import com.akine.organization.spi.ConsultorioSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Asienta en la caja la plata que un pago a un beneficiario hizo salir, <b>en la misma transaccion
 * del pago</b>.
 *
 * <h2>Gemelo de {@link CajaDeCobro}, y a proposito</h2>
 *
 * <p>Esta clase existe para que <b>no haya un segundo camino por el que salga plata</b>. Era el
 * error mas facil de cometer en esta etapa: una tabla {@code egreso_movimiento} propia, o un
 * {@code UPDATE} directo sobre {@code jornada_caja}, harian que {@code saldo_arqueo} tuviera
 * <b>dos duenos</b> y que el arqueo dejara de ser la suma de un solo ledger. La caja seguiria
 * cuadrando en los tests y dejaria de cuadrar en produccion el primer dia que alguien pagara un
 * alquiler.
 *
 * <p>Por eso todo pasa por {@link MovimientoCajaService#asentar}, exactamente como el cobro. Lo que
 * se hereda, sin excepciones nuevas:
 *
 * <ul>
 *   <li><b>Solo el efectivo afecta el arqueo.</b> Pagar por transferencia asienta su movimiento
 *       —se lista y se totaliza— y no toca el cajon: esa plata nunca estuvo ahi.</li>
 *   <li><b>El efectivo exige jornada abierta.</b> La plata sale del cajon igual; si el sistema no
 *       sabe de que jornada, el arqueo de ese dia no cuadra contra nada.</li>
 *   <li><b>El saldo de la caja nunca queda negativo.</b> Un pago que no entra en el cajon
 *       <b>se rechaza</b>; no deja la caja en rojo. Lo decide una condicion del motor, asi que dos
 *       pagos concurrentes no pueden colarse los dos.</li>
 * </ul>
 */
@Component
class CajaDeEgreso {

	private final JornadaCajaRepositoryPort jornadas;
	private final MovimientoCajaService movimientos;

	CajaDeEgreso(JornadaCajaRepositoryPort jornadas, MovimientoCajaService movimientos) {
		this.jornadas = jornadas;
		this.movimientos = movimientos;
	}

	/**
	 * Un movimiento de egreso por el pago.
	 *
	 * <p>{@code tipo_origen = PAGO_EGRESO} y {@code referencia_origen = pagoId}: es el movimiento el
	 * que apunta al pago. El unique de V54 garantiza que un pago produzca <b>a lo sumo uno</b>.
	 *
	 * @throws CajaNoAbiertaException         se paga en efectivo y la sede no tiene caja abierta (409)
	 * @throws CajaSaldoInsuficienteException el pago dejaria el cajon en negativo (409)
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	MovimientoCaja registrarSalida(
			long organizationId, ConsultorioSnapshot sede, long pagoId, MedioDePago medio,
			BigDecimal importe, String moneda, String concepto, Instant cuando, long actorCuentaId) {

		JornadaCaja jornada = jornadas.findAbierta(organizationId, sede.id()).orElse(null);

		// La fecha del hecho es la de la jornada cuando hay una: un turno de caja abierto anoche y
		// cerrado hoy a la madrugada es UN arqueo, y partirlo por medianoche haria que la suma del
		// ledger de la jornada no diera su propio saldo. Sin jornada, la del dia de la sede.
		LocalDate fechaNegocio = jornada != null
				? jornada.getFechaNegocio()
				: CajaAcceso.fechaDeNegocio(sede, cuando);

		return movimientos.asentar(
				organizationId, sede.id(), jornada, fechaNegocio,
				TipoMovimiento.EGRESO, medio, importe, moneda,
				concepto, null,
				OrigenMovimiento.PAGO_EGRESO, pagoId, null,
				cuando, actorCuentaId, null, null);
	}
}
