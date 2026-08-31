package com.akine.clinical.domain.exception;

/**
 * El antecedente pedido no existe dentro del alcance del actor.
 *
 * <p>Mismo criterio que {@link HistoriaClinicaNotAccessibleException}: "no existe" y "es de otra
 * organizacion" son indistinguibles desde afuera a proposito.
 */
public class AntecedenteNotAccessibleException extends RuntimeException {

	private final long antecedenteId;

	public AntecedenteNotAccessibleException(long antecedenteId) {
		super("No existe un antecedente clinico accesible con id " + antecedenteId);
		this.antecedenteId = antecedenteId;
	}

	public long getAntecedenteId() {
		return antecedenteId;
	}
}
