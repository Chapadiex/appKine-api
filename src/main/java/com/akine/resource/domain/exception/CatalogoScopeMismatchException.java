package com.akine.resource.domain.exception;

/**
 * Se intento colgar un concepto GLOBAL de uno CONTEXTUAL (409).
 *
 * <p>Es el invariante que ninguna clave foranea puede expresar, porque una FK compara ids y no
 * alcances: <b>una practica global solo puede pertenecer a una especialidad global</b>, y una
 * vigencia de un nomenclador global solo puede codificar una practica global. Al reves si vale:
 * un tenant puede colgar su practica propia de una especialidad de plataforma, que es el caso
 * frecuente.
 *
 * <p>Sin esta comprobacion, un tenant decidiria por si solo el destino de un concepto que ven
 * todos los demas: dar de baja SU especialidad dejaria huerfana una practica de plataforma que
 * el resto del SaaS sigue usando.
 *
 * <p>409 y no 400: los dos ids existen y son validos; lo que no admite la combinacion es el
 * estado del sistema.
 */
public class CatalogoScopeMismatchException extends RuntimeException {

	private final long referenciaId;

	public CatalogoScopeMismatchException(long referenciaId) {
		super("Un concepto global no puede depender del concepto contextual " + referenciaId);
		this.referenciaId = referenciaId;
	}

	public long getReferenciaId() {
		return referenciaId;
	}
}
