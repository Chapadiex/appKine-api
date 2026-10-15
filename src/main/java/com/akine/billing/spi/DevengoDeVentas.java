package com.akine.billing.spi;

import java.math.BigDecimal;
import java.time.Instant;

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

	/** La deuda que la venta dejo. */
	record DeudaDeVenta(long obligacionId, BigDecimal importe, BigDecimal saldo, String moneda,
						String estado, boolean devengadaAhora) {
	}
}
