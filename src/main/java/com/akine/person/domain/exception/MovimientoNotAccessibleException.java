package com.akine.person.domain.exception;

/**
 * El movimiento no existe, es de otra organizacion o es de otra autorizacion (404).
 *
 * <p>Los tres casos colapsan en la misma respuesta, igual que con la persona y con la
 * autorizacion: distinguirlos confirmaria que ese id existe en algun lado y bastaria probar ids
 * consecutivos para mapear el ledger de otro centro (ADR-0018).
 */
public class MovimientoNotAccessibleException extends RuntimeException {

	private final long movimientoId;

	public MovimientoNotAccessibleException(long movimientoId) {
		super("El movimiento no existe o no es accesible");
		this.movimientoId = movimientoId;
	}

	public long getMovimientoId() {
		return movimientoId;
	}
}
