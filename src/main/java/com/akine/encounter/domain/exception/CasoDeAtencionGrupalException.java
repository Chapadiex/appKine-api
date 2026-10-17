package com.akine.encounter.domain.exception;

import com.akine.platform.spi.problem.ConTipoDeProblema;
import com.akine.platform.spi.problem.ProblemType;

/**
 * Se intento cambiar o quitar el Caso de una Sesion de origen grupal. <b>409</b>. AKINE-08.05.
 *
 * <p>El Caso de esa atencion lo eligio la derivacion de la que salio (08.04): derivar <i>es</i>
 * elegir el Caso. Cambiarlo aca dejaria la Sesion numerada en un Caso distinto del que la
 * derivacion declara, y la pregunta "por que este paciente esta en este tratamiento" quedaria con
 * dos respuestas. El camino es derivar al otro Caso y atender desde esa derivacion.
 */
public class CasoDeAtencionGrupalException extends RuntimeException implements ConTipoDeProblema {

	private final Long sesionId;

	public CasoDeAtencionGrupalException(Long sesionId) {
		super("La sesion " + sesionId + " es de origen grupal: su caso lo fija la derivacion");
		this.sesionId = sesionId;
	}

	@Override
	public ProblemType tipoDeProblema() {
		return ProblemType.CASO_DE_ATENCION_GRUPAL;
	}

	public Long getSesionId() {
		return sesionId;
	}
}
