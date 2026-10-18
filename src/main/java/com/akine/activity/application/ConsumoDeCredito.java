package com.akine.activity.application;

/**
 * Que le hizo una asistencia al saldo de pases de la persona (RF-M29-003, RN-M29-005).
 *
 * @param paseId el pase afectado; {@code null} si no hubo movimiento
 * @param saldo  el saldo del pase despues del movimiento; {@code null} si no hubo movimiento
 */
public record ConsumoDeCredito(Efecto efecto, Long paseId, Integer saldo) {

	public enum Efecto {
		/** Se desconto un credito. */
		CONSUMIDO,
		/** Una correccion a ausente devolvio el credito que la asistencia tenia tomado. */
		DEVUELTO,
		/** La asistencia ya tenia su credito tomado (o devuelto): nada que hacer. */
		SIN_CAMBIO,
		/**
		 * La oferta cobra por pack y la persona no tiene un pase elegible. La asistencia queda
		 * registrada igual —asistencia y economia son independientes (CA-M19-009-06)— y queda a la
		 * vista para regularizar.
		 */
		SIN_PASE_ELEGIBLE,
		/** La oferta no cobra por pack: la asistencia no mueve creditos. */
		NO_APLICA
	}

	/** El mismo efecto, sin decir que pase era ni cuanto le queda (U-46 (c)). */
	public ConsumoDeCredito sinPase() {
		return new ConsumoDeCredito(efecto, null, null);
	}

	public static ConsumoDeCredito noAplica() {
		return new ConsumoDeCredito(Efecto.NO_APLICA, null, null);
	}
}
