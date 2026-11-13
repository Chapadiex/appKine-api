package com.akine.scheduling.application;

import java.time.Instant;

/**
 * Lo que hay que decir para mover un turno.
 *
 * <p>No lleva la persona ni la oferta: reprogramar <b>no</b> es reservar de nuevo. Cambiar de
 * paciente o de servicio sobre un turno existente seria otro turno con el historial equivocado
 * pegado atras; lo que corresponde ahi es cancelar y reservar.
 *
 * @param inicio         instante de inicio del slot nuevo, tal como lo devolvio la agenda
 * @param profesionalId  membership del profesional en el horario nuevo. Puede ser otro: mover un
 *                       turno porque el profesional se ausento es el caso mas frecuente. {@code
 *                       null} solo si la oferta no requiere profesional
 * @param motivo         por que se mueve. <b>Obligatorio</b>: DP-04 exige motivo y auditoria para
 *                       toda transicion que altere un turno pendiente
 * @param expectedVersion version que el cliente leyo. Sin ella, dos operadores sobre el mismo turno
 *                       se pisan en silencio
 */
public record ReprogramacionCommand(
		Instant inicio,
		Long profesionalId,
		String motivo,
		long expectedVersion,
		Long espacioId) {

	public ReprogramacionCommand {
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("El motivo de reprogramacion es obligatorio (DP-04)");
		}
		motivo = motivo.strip();
	}

	/** Sin box elegido: el sistema asigna el primero libre, como hasta la agenda operativa. */
	public ReprogramacionCommand(Instant inicio, Long profesionalId, String motivo, long expectedVersion) {
		this(inicio, profesionalId, motivo, expectedVersion, null);
	}
}
