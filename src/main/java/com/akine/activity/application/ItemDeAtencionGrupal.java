package com.akine.activity.application;

import com.akine.encounter.spi.AtencionDeParticipante;
import com.akine.platform.spi.problem.ProblemType;

/**
 * El resultado de <b>un</b> participante dentro de un lote de atencion grupal (AKINE-08.05).
 *
 * <p>O trae la atencion, o trae el error de ese participante; nunca los dos, nunca ninguno. El
 * {@code problemType} es el mismo que habria viajado en el Problem Detail individual.
 */
public record ItemDeAtencionGrupal(
		long inscripcionId,
		AtencionDeParticipante atencion,
		String problemType,
		String detalle) {

	public static ItemDeAtencionGrupal ok(long inscripcionId, AtencionDeParticipante atencion) {
		return new ItemDeAtencionGrupal(inscripcionId, atencion, null, null);
	}

	public static ItemDeAtencionGrupal error(long inscripcionId, ProblemType tipo, String detalle) {
		return new ItemDeAtencionGrupal(inscripcionId, null, tipo.value(), detalle);
	}

	public boolean exitoso() {
		return atencion != null;
	}
}
