package com.akine.clinical.domain.exception;

import com.akine.platform.spi.problem.ConTipoDeProblema;
import com.akine.platform.spi.problem.ProblemType;

/**
 * La participacion no tiene la derivacion vigente que una atencion necesita (409). AKINE-08.05.
 *
 * <p>Cubre dos casos con el mismo remedio: no hay ninguna derivacion vigente, o la que el pedido
 * nombro no esta vigente o no cuelga de esa participacion. <b>Derivar no es atender</b> (08.04), y
 * al reves tampoco: no se atiende a quien nadie derivo. Una derivacion de otra participacion se
 * trata igual que una inexistente para esta, porque para esta lo es.
 */
public class ParticipacionSinDerivacionException extends RuntimeException implements ConTipoDeProblema {

	private final long participacionId;
	private final Long derivacionId;

	public ParticipacionSinDerivacionException(long participacionId, Long derivacionId) {
		super(derivacionId == null
				? "La participacion " + participacionId + " no tiene ninguna derivacion clinica vigente"
				: "La derivacion " + derivacionId + " no esta vigente para la participacion "
						+ participacionId);
		this.participacionId = participacionId;
		this.derivacionId = derivacionId;
	}

	@Override
	public ProblemType tipoDeProblema() {
		return ProblemType.PARTICIPACION_SIN_DERIVACION;
	}

	public long getParticipacionId() {
		return participacionId;
	}

	public Long getDerivacionId() {
		return derivacionId;
	}
}
