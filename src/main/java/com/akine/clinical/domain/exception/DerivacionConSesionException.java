package com.akine.clinical.domain.exception;

import com.akine.platform.spi.problem.ConTipoDeProblema;
import com.akine.platform.spi.problem.ProblemType;

/**
 * La derivacion no se revierte porque de ella cuelga una Sesion (409). AKINE-08.05.
 *
 * <p>Es el cerrojo que 08.04 dejo escrito en el javadoc de {@code revertir} para cuando existiera
 * la tabla que lo hace posible. Revertir el vinculo dejaria una atencion clinica documentada sin
 * la procedencia que la explica, y la Sesion no se borra (regla maestra 10).
 */
public class DerivacionConSesionException extends RuntimeException implements ConTipoDeProblema {

	private final long derivacionId;

	public DerivacionConSesionException(long derivacionId) {
		super("La derivacion " + derivacionId + " ya tiene una sesion clinica y no se puede revertir");
		this.derivacionId = derivacionId;
	}

	@Override
	public ProblemType tipoDeProblema() {
		return ProblemType.DERIVACION_CON_SESION;
	}

	public long getDerivacionId() {
		return derivacionId;
	}
}
