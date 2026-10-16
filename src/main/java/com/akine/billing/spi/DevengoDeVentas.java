package com.akine.billing.spi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.Set;

/**
 * Devengo de la deuda de una venta (AKINE-08.06, RF-M18-009). Es el primer contrato publico de
 * {@code billing} y lo consume {@code activity}, que vende packs y pases.
 *
 * <h2>La direccion es {@code activity -> billing.spi}, al reves que 07.01</h2>
 *
 * <p>En 07.01 {@code billing} observa a {@code encounter} porque cerrar una atencion es un acto
 * clinico que no puede saber que existe facturacion. Vender un pack es un acto <b>comercial</b>: el
 * modulo que vende tiene que saber que genera deuda, y CA-M29-002-04 exige pase y obligacion en la
 * misma transaccion, cosa que un observador posterior al commit no puede dar.
 *
 * <h2>Devengar no es cobrar (DP-06)</h2>
 *
 * <p>La implementacion crea <b>exactamente una obligacion</b> y nada mas: ni cobro ni movimiento
 * de caja. Se paga despues con {@code POST /cobros}.
 */
public interface DevengoDeVentas {

	/**
	 * Devenga la deuda de la venta de un pase, <b>en la transaccion del llamador</b>.
	 *
	 * <p>Idempotente por pase: si ya existe la obligacion viva de esa venta, la devuelve sin
	 * escribir.
	 */
	DeudaDeVenta devengarPorVentaDePase(VentaDePase venta);

	/**
	 * Devenga la deuda de <b>un periodo</b> de abono (AKINE-08.08, RF-M18-010), en la transaccion del
	 * llamador. El abono es un pase con periodo, asi que la forma es la misma y la idempotencia
	 * tambien: una obligacion por pase, o sea por periodo. La unica diferencia es el origen
	 * ({@code VENTA_ABONO}).
	 */
	DeudaDeVenta devengarPorVentaDeAbono(VentaDePase venta);

	/**
	 * Anula la deuda de la venta de un pase o abono que se anula (AKINE-08.07), <b>en la transaccion
	 * del llamador</b> y con las reglas de siempre de {@code billing}: una deuda con cobros imputados
	 * <b>no se anula</b> —lo que corresponde es anular o reintegrar el cobro (RF-M19-010), que es un
	 * acto de caja con su propio registro—.
	 *
	 * <p>Si tiene cobros <b>no escribe nada</b> y lo informa ({@link AnulacionDeDeuda.Efecto#CON_COBROS}):
	 * el llamador decide rechazar sin dejar el pase anulado y la deuda viva. Una deuda ya anulada se
	 * informa sin escribir. No la anula si cambio desde que se leyo: la escritura lleva su version y
	 * un cobro concurrente la hace fallar con conflicto de concurrencia.
	 */
	AnulacionDeDeuda anularDeudaDeVenta(
			long organizationId, long paseId, long actorCuentaId, String motivo, Instant ahora);

	/**
	 * De esos pases, los que tienen la deuda de su venta <b>viva y con cobros imputados</b>: la misma
	 * condicion por la que {@link #anularDeudaDeVenta} responde {@link AnulacionDeDeuda.Efecto#CON_COBROS}
	 * (saldo distinto del importe original en una obligacion no anulada). Solo lee: es lo que deja a
	 * {@code activity} calcular al leer si un pase es anulable, sin persistirlo (desde 0.111.0).
	 */
	Set<Long> ventasConCobros(long organizationId, Collection<Long> paseIds);

	/**
	 * La venta de un pase, con el precio congelado en el pase.
	 *
	 * @param nombre lo que se vendio, tal como quedo en el snapshot del pase
	 */
	record VentaDePase(
			long organizationId,
			long consultorioId,
			long paseId,
			long personaId,
			long ofertaId,
			String nombre,
			BigDecimal importe,
			String moneda,
			long actorCuentaId,
			Instant ocurridaEn) {
	}

	/** Lo que paso con la deuda de una venta anulada. {@code obligacionId} es null sin deuda. */
	record AnulacionDeDeuda(Efecto efecto, Long obligacionId, BigDecimal importe, BigDecimal saldo,
							String moneda, String estado) {

		public enum Efecto {
			/** Se anulo ahora. */
			ANULADA,
			/** Ya estaba anulada: no se escribio nada. */
			YA_ANULADA,
			/** Tiene cobros imputados: no se escribio nada y la venta no se puede anular. */
			CON_COBROS,
			/** La venta no tiene deuda viva (no deberia ocurrir: la venta la devenga siempre). */
			SIN_DEUDA
		}
	}

	/** La deuda que la venta dejo. */
	record DeudaDeVenta(long obligacionId, BigDecimal importe, BigDecimal saldo, String moneda,
						String estado, boolean devengadaAhora) {
	}
}
