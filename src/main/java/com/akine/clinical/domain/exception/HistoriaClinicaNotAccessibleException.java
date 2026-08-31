package com.akine.clinical.domain.exception;

/**
 * La historia pedida no existe dentro del alcance del actor.
 *
 * <p><b>Es la misma excepcion para "no existe" y para "es de otro tenant"</b>, y esa es la
 * decision. La regla que 01.01 dejo fijada —cross-tenant es 404, nunca 403— existe porque un 403
 * confirma que el recurso existe: bastaria probar ids consecutivos para censar las historias
 * clinicas de otro centro. En un modulo clinico eso deja de ser un problema de aislamiento y pasa
 * a ser uno de privacidad.
 */
public class HistoriaClinicaNotAccessibleException extends RuntimeException {

	private final long referencia;

	public HistoriaClinicaNotAccessibleException(long referencia) {
		super("No existe una historia clinica accesible para la referencia " + referencia);
		this.referencia = referencia;
	}

	public long getReferencia() {
		return referencia;
	}
}
