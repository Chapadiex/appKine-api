package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * La suma de los medios no da el total del cobro. <b>400</b>.
 *
 * <p>RN-M19: es la invariante del cobro y no se puede expresar como CHECK —MySQL no admite
 * subconsultas en un CHECK— asi que la verifica la aplicacion. Sin ella, un cobro de 8500 con un
 * medio de 850 por un cero de menos entra igual, la deuda queda saldada y en la caja falta plata
 * que nadie va a poder explicar.
 */
public class MediosNoSumanException extends RuntimeException {

	private final BigDecimal sumaDeMedios;
	private final BigDecimal total;

	public MediosNoSumanException(BigDecimal sumaDeMedios, BigDecimal total) {
		super("Los medios de pago suman " + sumaDeMedios + " y el cobro es de " + total);
		this.sumaDeMedios = sumaDeMedios;
		this.total = total;
	}

	public BigDecimal getSumaDeMedios() {
		return sumaDeMedios;
	}

	public BigDecimal getTotal() {
		return total;
	}
}
