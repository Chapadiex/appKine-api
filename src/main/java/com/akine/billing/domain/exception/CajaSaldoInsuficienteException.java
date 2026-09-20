package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * El egreso saca mas plata de la que hay en el cajon. <b>409</b>.
 *
 * <p><b>Un egreso no puede dejar la caja en negativo</b>, y no por una regla de negocio sino por
 * una del mundo fisico: un cajon no puede tener menos de cero pesos. Un saldo negativo significaria
 * que el registro se desprendio de la realidad, y a partir de ahi ningun arqueo sirve.
 *
 * <p>Lo expresa una <b>condicion, no un lock</b>:
 * {@code SET saldo_arqueo = saldo_arqueo - :importe WHERE saldo_arqueo >= :importe}. Es atomico, no
 * lee antes —que es donde se cuela la ventana— y no puede deadlockear. Cero filas afectadas es esto.
 *
 * <p>409 y no 400: el importe era valido cuando se compuso y lo que cambio es el estado del
 * servidor, probablemente porque otro egreso se llevo la plata primero.
 */
public class CajaSaldoInsuficienteException extends RuntimeException {

	private final long jornadaId;
	private final BigDecimal importeIntentado;
	private final BigDecimal saldoDisponible;

	public CajaSaldoInsuficienteException(
			long jornadaId, BigDecimal importeIntentado, BigDecimal saldoDisponible) {

		super("La jornada de caja " + jornadaId + " no tiene " + importeIntentado
				+ " para egresar: disponible " + saldoDisponible);
		this.jornadaId = jornadaId;
		this.importeIntentado = importeIntentado;
		this.saldoDisponible = saldoDisponible;
	}

	public long getJornadaId() {
		return jornadaId;
	}

	public BigDecimal getImporteIntentado() {
		return importeIntentado;
	}

	public BigDecimal getSaldoDisponible() {
		return saldoDisponible;
	}
}
