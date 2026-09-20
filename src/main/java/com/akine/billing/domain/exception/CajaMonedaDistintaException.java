package com.akine.billing.domain.exception;

/**
 * El movimiento viene en una moneda distinta de la de la jornada. <b>409</b>.
 *
 * <p>La moneda se fija al abrir la caja. Un arqueo que suma pesos con dolares no se puede contar, y
 * convertir exigiria una cotizacion que es una decision de negocio que nadie tomo. Rechazarlo es
 * preferible a producir un saldo que no significa nada. Mismo criterio que la moneda unica de
 * {@code CobroService}.
 */
public class CajaMonedaDistintaException extends RuntimeException {

	private final String monedaDeLaCaja;
	private final String monedaDelMovimiento;

	public CajaMonedaDistintaException(String monedaDeLaCaja, String monedaDelMovimiento) {
		super("La caja esta en " + monedaDeLaCaja + " y el movimiento viene en "
				+ monedaDelMovimiento);
		this.monedaDeLaCaja = monedaDeLaCaja;
		this.monedaDelMovimiento = monedaDelMovimiento;
	}

	public String getMonedaDeLaCaja() {
		return monedaDeLaCaja;
	}

	public String getMonedaDelMovimiento() {
		return monedaDelMovimiento;
	}
}
