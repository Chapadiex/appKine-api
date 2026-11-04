package com.akine.scheduling.application;

import java.time.Instant;

/**
 * Un cambio en la agenda de un profesional, listo para mostrar (historial de agenda).
 *
 * <p>{@code antes} y {@code despues} son textos ya armados ("Lun 08:00–14:00" → "Lun 09:00–14:00",
 * "Lun 10/11 10:00 · Sede Centro · Gomez, Laura" → ...); los ids viajan aparte para enlazar.
 *
 * @param actorRol      rol con el que opero quien hizo el cambio; {@code null} en los eventos anteriores
 *                      a la agenda de profesionales, que no lo guardaban
 * @param profesionalId la membership del profesional en el evento (la de esa sede)
 * @param turnoInicio   el horario del turno, en los eventos de turno
 * @param personaId     el paciente del turno: solo si el lector tiene {@code turno:read} en esa sede
 * @param personaNombre su nombre, con la misma condicion
 * @param cambioDeProfesional {@code true} en una reprogramacion que paso el turno a otro profesional
 */
public record CambioDeAgendaView(
		long id,
		Instant ocurridoEn,
		TipoDeCambioDeAgenda tipo,
		String entidad,
		Long entidadId,
		Long consultorioId,
		String consultorioNombre,
		Long actorCuentaId,
		String actorNombre,
		String actorRol,
		String motivo,
		String antes,
		String despues,
		Long profesionalId,
		String profesionalNombre,
		Long turnoId,
		Instant turnoInicio,
		Long personaId,
		String personaNombre,
		boolean cambioDeProfesional) {
}
