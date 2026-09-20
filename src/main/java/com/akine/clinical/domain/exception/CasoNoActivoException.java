package com.akine.clinical.domain.exception;

/**
 * Se intento crear o activar un plan sobre un Caso que ya no esta ACTIVO (409).
 *
 * <p>Existe aparte de {@link CasoClinicoCerradoException} porque dice otra cosa y lleva a otra
 * accion. Aquella la lanza el propio Caso cuando alguien quiere <b>editarlo</b>; esta la lanza el
 * Plan cuando el Caso que lo tendria que sostener esta cerrado, y lo que la pantalla tiene que
 * ofrecer es <b>reabrir el caso con motivo</b> antes de planificar nada.
 *
 * <p>Un plan de tratamiento para un problema que el centro dio por terminado es un tratamiento sin
 * problema que tratar: RN-M11-002 ata el plan al Caso, y esa atadura no es decorativa.
 */
public class CasoNoActivoException extends RuntimeException {

	private final long casoClinicoId;

	public CasoNoActivoException(long casoClinicoId) {
		super("El caso clinico " + casoClinicoId
				+ " no esta activo y no admite planes de tratamiento");
		this.casoClinicoId = casoClinicoId;
	}

	public long getCasoClinicoId() {
		return casoClinicoId;
	}
}
