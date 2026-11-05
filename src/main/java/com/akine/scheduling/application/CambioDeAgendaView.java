package com.akine.scheduling.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Un cambio en la agenda de un profesional, listo para mostrar (historial de agenda).
 *
 * <p>{@code antes} y {@code despues} son textos ya armados ("Lun 08:00–14:00" → "Lun 09:00–14:00",
 * "Lun 10/11 10:00 · Sede Centro · Gomez, Laura" → ...); los ids viajan aparte para enlazar.
 *
 * @param actorRol      rol con el que opero quien hizo el cambio; {@code null} en los eventos anteriores
 *                      a la agenda de profesionales, que no lo guardaban
 * @param profesionalId la membership del profesional en el evento (la de esa sede). En un cierre de sede,
 *                      la de la persona consultada en esa sede
 * @param turnoInicio   el horario del turno, en los eventos de turno
 * @param personaId     el paciente del turno: solo si el lector tiene {@code turno:read} en esa sede
 * @param personaNombre su nombre, con la misma condicion
 * @param cambioDeProfesional {@code true} en una reprogramacion o un traslado que paso el turno a otra persona
 * @param turnoNuevoId  en un traslado, el turno que nacio en la sede de destino
 * @param cierreDeSede  en un {@code CIERRE_DE_SEDE_*}, el periodo y lo que le toco a la persona
 * @param operacionId   id comun de las filas de auditoria de una misma operacion (traslados desde la
 *                      version 0.157.0); {@code null} en las anteriores
 * @param eventosRelacionados los hechos auditados que esta fila resume (la cancelacion y la reserva de un
 *                      traslado); vacio en el resto
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
		boolean cambioDeProfesional,
		Long turnoNuevoId,
		CierreDeSedeView cierreDeSede,
		String operacionId,
		List<EventoRelacionadoView> eventosRelacionados) {

	public CambioDeAgendaView {
		eventosRelacionados = eventosRelacionados == null ? List.of() : List.copyOf(eventosRelacionados);
	}

	/**
	 * Un cierre de la sede entera visto desde la agenda de una persona. Los conteos son de AHORA (se
	 * calculan al leer), sobre los turnos de esa persona en esa sede que empiezan en el periodo.
	 *
	 * @param ultimoDia          el ultimo dia afectado (la tabla guarda el fin exclusivo)
	 * @param horaDesde          {@code null} junto con {@code horaHasta} = dia completo
	 * @param vigente            {@code false} si el cierre ya se dio de baja
	 * @param conHorarioHabitual la persona tenia horario habitual en la sede en ese periodo
	 * @param turnosPendientes   reservados o confirmados que siguen ahi: los "a reubicar" de la agenda
	 *                           operativa ({@code fueraDeDisponibilidad})
	 */
	public record CierreDeSedeView(
			long excepcionId,
			String motivo,
			LocalDate fechaDesde,
			LocalDate ultimoDia,
			LocalTime horaDesde,
			LocalTime horaHasta,
			boolean vigente,
			boolean conHorarioHabitual,
			int turnosEnElPeriodo,
			int turnosPendientes,
			int turnosCancelados) {
	}

	/** Un hecho de la auditoria que una fila visible resume. */
	public record EventoRelacionadoView(
			long id,
			Instant ocurridoEn,
			String eventType,
			Long turnoId,
			Long consultorioId) {
	}
}
