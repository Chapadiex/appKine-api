package com.akine.clinical.domain.exception;

/**
 * La entrada clinica pedida no existe dentro del alcance del actor.
 *
 * <p>Mismo criterio que {@link HistoriaClinicaNotAccessibleException} y por el mismo motivo, que
 * en este modulo pesa mas que en ningun otro: "no existe", "es de otro tenant" y "es de otra
 * historia" son <b>indistinguibles desde afuera a proposito</b>. Un 403 confirmaria que la fila
 * existe, y probar ids consecutivos alcanzaria para censar cuantas entradas clinicas tiene otro
 * centro — que deja de ser un problema de aislamiento y pasa a ser uno de privacidad.
 */
public class EntradaClinicaNotAccessibleException extends RuntimeException {

	private final long entradaClinicaId;

	public EntradaClinicaNotAccessibleException(long entradaClinicaId) {
		super("No existe una entrada clinica accesible con id " + entradaClinicaId);
		this.entradaClinicaId = entradaClinicaId;
	}

	public long getEntradaClinicaId() {
		return entradaClinicaId;
	}
}
