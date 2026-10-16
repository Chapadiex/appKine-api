package com.akine.clinical.domain.exception;

import com.akine.platform.spi.problem.ConTipoDeProblema;
import com.akine.platform.spi.problem.ProblemType;

import java.util.List;

/**
 * La participacion esta derivada a mas de un Caso y el pedido no dijo a cual (409). AKINE-08.05.
 *
 * <p>No se elige por el cliente: la Sesion se numera dentro del Caso (RN-M14-010), y numerarla en
 * el equivocado no se corrige sin reescribir historia clinica. Lleva los ids de las derivaciones
 * vigentes —ids, nada clinico— para que la pantalla ofrezca la eleccion.
 */
public class DerivacionAmbiguaException extends RuntimeException implements ConTipoDeProblema {

	private final List<Long> derivacionIds;

	public DerivacionAmbiguaException(long participacionId, List<Long> derivacionIds) {
		super("La participacion " + participacionId + " tiene " + derivacionIds.size()
				+ " derivaciones vigentes: hay que elegir a cual corresponde la atencion");
		this.derivacionIds = List.copyOf(derivacionIds);
	}

	@Override
	public ProblemType tipoDeProblema() {
		return ProblemType.DERIVACION_AMBIGUA;
	}

	public List<Long> getDerivacionIds() {
		return derivacionIds;
	}
}
