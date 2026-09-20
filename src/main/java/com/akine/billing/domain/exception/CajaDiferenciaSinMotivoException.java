package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * El arqueo no cuadra y el cierre no trae motivo. <b>400</b>.
 *
 * <p>RN-M20-004: toda diferencia queda registrada y <b>justificada</b>. La diferencia no se rechaza
 * —eso dejaria al centro sin poder cerrar el dia en que realmente falta plata, que es el dia en que
 * el registro importa— y no se ajusta con un movimiento que la haga desaparecer; lo unico que se
 * exige es que alguien escriba por que.
 *
 * <p>400 y no 409 a proposito: el estado del servidor esta perfecto y lo que falta es un campo del
 * cuerpo. La validacion no se puede hacer con {@code @Valid} porque depende de la diferencia, que
 * la calcula el servidor contra su propio saldo.
 *
 * <p>La reciproca tambien se rechaza —motivo con diferencia cero— y la hace cumplir un CHECK de la
 * base: un motivo que a veces adorna un cierre correcto deja de leerse en los cierres que si
 * importan.
 */
public class CajaDiferenciaSinMotivoException extends RuntimeException {

	private final BigDecimal diferencia;

	public CajaDiferenciaSinMotivoException(BigDecimal diferencia) {
		super("El arqueo arroja una diferencia de " + diferencia + " y exige un motivo declarado");
		this.diferencia = diferencia;
	}

	public BigDecimal getDiferencia() {
		return diferencia;
	}
}
