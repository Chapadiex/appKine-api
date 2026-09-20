package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * El pago excede lo que todavia se debe. <b>409</b>.
 *
 * <p>Lo expresa una <b>condicion, no un lock</b>:
 * {@code SET saldo_pendiente = saldo_pendiente - :importe WHERE saldo_pendiente >= :importe}. Es
 * atomico, no lee antes —que es donde se cuela la ventana entre dos operadores— y no puede
 * deadlockear. Cero filas afectadas es esto.
 *
 * <p>409 y no 400: el importe era valido cuando se compuso, y lo que cambio es el estado del
 * servidor porque otro pago se llevo el saldo primero. Reintentar con el egreso recargado es la
 * accion correcta, y un 400 sugeriria que el operador se equivoco.
 */
public class EgresoSaldoInsuficienteException extends RuntimeException {

	private final Long egresoId;
	private final BigDecimal importeIntentado;
	private final BigDecimal saldoDisponible;

	public EgresoSaldoInsuficienteException(
			Long egresoId, BigDecimal importeIntentado, BigDecimal saldoDisponible) {

		super("El egreso " + egresoId + " no debe " + importeIntentado
				+ ": pendiente " + saldoDisponible);
		this.egresoId = egresoId;
		this.importeIntentado = importeIntentado;
		this.saldoDisponible = saldoDisponible;
	}

	public Long getEgresoId() {
		return egresoId;
	}

	public BigDecimal getImporteIntentado() {
		return importeIntentado;
	}

	public BigDecimal getSaldoDisponible() {
		return saldoDisponible;
	}
}
