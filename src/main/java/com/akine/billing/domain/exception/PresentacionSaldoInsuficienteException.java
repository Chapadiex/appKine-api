package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * El pago o el debito es mayor que lo que queda por explicar del lote. <b>409</b>.
 *
 * <p><b>Un financiador que paga de mas no esta pagando este lote:</b> esta pagando otra cosa, o hay
 * un error de imputacion. Absorberlo en silencio produciria una cuenta corriente que cuadra por
 * casualidad. Misma decision que 07.02 tomo con la sobreimputacion.
 *
 * <p>Lo expresa una <b>condicion, no un lock</b>:
 * {@code SET saldo = saldo - :importe WHERE saldo >= :importe}. Cero filas afectadas es esto, y es
 * lo que resuelve el caso que rompe el diseño: el aviso de debito y la transferencia cargados a la
 * vez sobre el mismo lote.
 *
 * <p>409 y no 400: el importe era valido cuando se compuso y lo que cambio es el estado del
 * servidor. {@code saldoDisponible} viaja para que el operador entienda que hubo un debito, en vez
 * de mirar un "no se puede" y volver a intentar.
 */
public class PresentacionSaldoInsuficienteException extends RuntimeException {

	private final long presentacionId;
	private final BigDecimal importeIntentado;
	private final BigDecimal saldoDisponible;

	public PresentacionSaldoInsuficienteException(
			long presentacionId, BigDecimal importeIntentado, BigDecimal saldoDisponible) {

		super("La presentacion " + presentacionId + " no tiene " + importeIntentado
				+ " por explicar: saldo " + saldoDisponible);
		this.presentacionId = presentacionId;
		this.importeIntentado = importeIntentado;
		this.saldoDisponible = saldoDisponible;
	}

	public long getPresentacionId() {
		return presentacionId;
	}

	public BigDecimal getImporteIntentado() {
		return importeIntentado;
	}

	public BigDecimal getSaldoDisponible() {
		return saldoDisponible;
	}
}
