package com.akine.billing.domain.exception;

import java.math.BigDecimal;

/**
 * Entraron o salieron movimientos entre que el operador conto y confirmo el cierre. <b>409</b>.
 *
 * <h2>Es el caso que rompe el diseno, y por eso existe esta clase</h2>
 *
 * <p>El administrativo abre el arqueo con un teorico de $142.500, cuenta los billetes durante
 * cuatro minutos y le dan $142.500. En ese rato su companera cobro $3.000 en efectivo en la otra
 * computadora de la misma sede.
 *
 * <p>Sin esta verificacion, el cierre registraria un faltante de $3.000 <b>que nunca existio</b>,
 * RN-M20-004 obligaria a escribir un motivo para justificar un desvio inventado, y los $3.000 estan
 * fisicamente en el cajon, asi que la jornada siguiente tambien arrancaria mal. Un control que
 * produce falsos positivos deja de leerse.
 *
 * <p>La verificacion viaja <b>dentro del mismo UPDATE que cierra</b>
 * ({@code AND saldo_arqueo = :esperado}): es control optimista de concurrencia aplicado a la
 * cantidad que significa algo, en vez de a un numero de version que las escrituras nativas del
 * saldo no incrementarian.
 *
 * <p>Lleva el teorico actual para que la pantalla pueda decir "entraron $3.000 mientras contabas"
 * en vez de un mensaje generico que empuje al operador a reintentar lo mismo.
 */
public class CajaSaldoCambioException extends RuntimeException {

	private final long jornadaId;
	private final BigDecimal saldoTeoricoEsperado;
	private final BigDecimal saldoTeoricoActual;

	public CajaSaldoCambioException(
			long jornadaId, BigDecimal saldoTeoricoEsperado, BigDecimal saldoTeoricoActual) {

		super("El saldo teorico de la jornada " + jornadaId + " cambio desde que se empezo a "
				+ "contar: se esperaba " + saldoTeoricoEsperado + " y ahora es " + saldoTeoricoActual);
		this.jornadaId = jornadaId;
		this.saldoTeoricoEsperado = saldoTeoricoEsperado;
		this.saldoTeoricoActual = saldoTeoricoActual;
	}

	public long getJornadaId() {
		return jornadaId;
	}

	public BigDecimal getSaldoTeoricoEsperado() {
		return saldoTeoricoEsperado;
	}

	public BigDecimal getSaldoTeoricoActual() {
		return saldoTeoricoActual;
	}
}
