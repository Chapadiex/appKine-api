package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.CambioDeAgendaView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Ver {@link CambioDeAgendaView}. */
@Schema(
		name = "CambioDeAgenda",
		description = "Un cambio en la agenda de un profesional: horario habitual, excepcion o bloqueo, cierre de la "
				+ "sede entera que lo afecto, o un turno. `antes` y `despues` ya vienen armados para mostrar. Un cambio "
				+ "de sede es UNA fila `TURNO_TRASLADO` (la cancelacion y la reserva que deja viajan en "
				+ "`eventosRelacionados`).")
public record CambioDeAgendaResponse(

		@Schema(description = "Id del hecho en la auditoria", example = "9812")
		long id,

		@Schema(example = "2026-11-04T15:20:00Z")
		Instant ocurridoEn,

		@Schema(description = "Que cambio", allowableValues = {"HORARIO_ALTA", "HORARIO_EDICION", "HORARIO_BAJA",
				"EXCEPCION_ALTA", "EXCEPCION_EDICION", "EXCEPCION_BAJA", "CIERRE_DE_SEDE_ALTA", "CIERRE_DE_SEDE_EDICION",
				"CIERRE_DE_SEDE_BAJA", "TURNO_RESERVA", "TURNO_SOBRETURNO",
				"TURNO_CONFIRMACION", "TURNO_CANCELACION", "TURNO_AUSENCIA", "TURNO_REPROGRAMACION",
				"TURNO_TRASLADO", "TURNO_OBSERVACIONES"}, example = "TURNO_REPROGRAMACION")
		String tipo,

		@Schema(description = "Sobre que es el cambio",
				allowableValues = {"HORARIO", "EXCEPCION", "CIERRE_DE_SEDE", "TURNO"})
		String entidad,

		@Schema(description = "Id del bloque, de la excepcion (tambien en un cierre de sede) o del turno", example = "4100")
		Long entidadId,

		@Schema(description = "Sede del cambio (en un traslado, la de origen)", example = "3")
		Long consultorioId,

		@Schema(example = "Sede Centro")
		String consultorioNombre,

		@Schema(description = "Cuenta que hizo el cambio. Ausente si lo hizo el sistema", example = "12")
		Long actorCuentaId,

		@Schema(example = "Ana Recepcion")
		String actorNombre,

		@Schema(description = "Rol con el que opero. Ausente en cambios anteriores a la agenda de profesionales",
				example = "ADMINISTRATIVO")
		String actorRol,

		@Schema(description = "Motivo declarado", example = "Licencia")
		String motivo,

		@Schema(description = "Como estaba. Ausente en un alta", example = "Lun 10/11 10:00 · Sede Centro · Gomez, Laura")
		String antes,

		@Schema(description = "Como quedo. Ausente en una baja", example = "Lun 10/11 11:00 · Sede Centro · Gomez, Laura")
		String despues,

		@Schema(description = "Membership del profesional en esa sede. En un cierre de sede, la del profesional "
				+ "consultado en esa sede", example = "31")
		Long profesionalId,

		@Schema(example = "Gomez, Laura")
		String profesionalNombre,

		@Schema(description = "El turno, en los cambios de turno (en un traslado, el original)", example = "4100")
		Long turnoId,

		@Schema(description = "Horario del turno, en los cambios de turno", example = "2026-11-10T14:00:00Z")
		Instant turnoInicio,

		@Schema(description = "Paciente del turno. Solo si quien lee tiene `turno:read` en esa sede (en un "
				+ "traslado, en la de origen o en la de destino)", example = "77")
		Long personaId,

		@Schema(description = "Nombre del paciente, con la misma condicion", example = "Perez, Juan")
		String personaNombre,

		@Schema(description = "`true` en una reprogramacion o un traslado que paso el turno a otra persona")
		boolean cambioDeProfesional,

		@Schema(description = "En un traslado, el turno que nacio en la sede de destino", example = "4101")
		Long turnoNuevoId,

		@Schema(description = "Solo en `CIERRE_DE_SEDE_*`: el cierre y lo que le dejo al profesional")
		CierreDeSedeEnHistorial cierreDeSede,

		@Schema(description = "Id comun de los hechos auditados de una misma operacion. Ausente en operaciones "
				+ "anteriores a 0.157.0", example = "6c1c0f8e-3f2a-4a51-9a3e-2f9f6f2e8a10")
		String operacionId,

		@ArraySchema(arraySchema = @Schema(description = "Los hechos de la auditoria que esta fila resume (en un "
				+ "traslado: la cancelacion del original y la reserva del nuevo). Vacio en el resto"))
		List<EventoRelacionadoDeAgenda> eventosRelacionados) {

	@Schema(name = "CierreDeSedeEnHistorial",
			description = "Un cierre de la sede entera visto desde la agenda de un profesional. Los conteos se "
					+ "calculan al leer, sobre los turnos de ese profesional en esa sede que empiezan en el periodo. "
					+ "Para ir a los turnos: la agenda operativa de `consultorioId` desde `fechaDesde`; los "
					+ "pendientes son los que muestra con `fueraDeDisponibilidad`.")
	public record CierreDeSedeEnHistorial(

			@Schema(description = "La excepcion de sede", example = "512")
			long excepcionId,

			@Schema(description = "Motivo de la excepcion", example = "FERIADO")
			String motivo,

			@Schema(description = "Primer dia afectado", example = "2026-11-16")
			LocalDate fechaDesde,

			@Schema(description = "Ultimo dia afectado, inclusive", example = "2026-11-16")
			LocalDate ultimoDia,

			@Schema(description = "HH:mm. Ausente junto con `horaHasta`: dia completo", example = "09:00")
			String horaDesde,

			@Schema(description = "HH:mm, exclusiva", example = "13:00")
			String horaHasta,

			@Schema(description = "`false` si el cierre ya se dio de baja")
			boolean vigente,

			@Schema(description = "El profesional tenia horario habitual en esa sede en el periodo")
			boolean conHorarioHabitual,

			@Schema(description = "Turnos del profesional que empiezan en el periodo, en cualquier estado", example = "6")
			int turnosEnElPeriodo,

			@Schema(description = "Reservados o confirmados que siguen ahi: pendientes de reubicacion", example = "2")
			int turnosPendientes,

			@Schema(description = "Cancelados", example = "4")
			int turnosCancelados) {

		static CierreDeSedeEnHistorial de(CambioDeAgendaView.CierreDeSedeView vista) {
			return vista == null ? null : new CierreDeSedeEnHistorial(vista.excepcionId(), vista.motivo(),
					vista.fechaDesde(), vista.ultimoDia(), hora(vista.horaDesde()), hora(vista.horaHasta()), vista.vigente(),
					vista.conHorarioHabitual(), vista.turnosEnElPeriodo(), vista.turnosPendientes(),
					vista.turnosCancelados());
		}

		private static String hora(LocalTime hora) {
			return hora == null ? null : String.format("%02d:%02d", hora.getHour(), hora.getMinute());
		}
	}

	@Schema(name = "EventoRelacionadoDeAgenda", description = "Un hecho de la auditoria que una fila del historial resume")
	public record EventoRelacionadoDeAgenda(

			@Schema(description = "Id del hecho en la auditoria", example = "9811")
			long id,

			@Schema(example = "2026-11-04T15:20:00Z")
			Instant ocurridoEn,

			@Schema(description = "Tipo de evento de la auditoria", example = "TURNO_CANCELADO")
			String eventType,

			@Schema(description = "El turno del hecho", example = "4100")
			Long turnoId,

			@Schema(description = "Sede del hecho", example = "3")
			Long consultorioId) {
	}

	public static CambioDeAgendaResponse de(CambioDeAgendaView vista) {
		return new CambioDeAgendaResponse(vista.id(), vista.ocurridoEn(), vista.tipo().name(), vista.entidad(),
				vista.entidadId(), vista.consultorioId(), vista.consultorioNombre(), vista.actorCuentaId(),
				vista.actorNombre(), vista.actorRol(), vista.motivo(), vista.antes(), vista.despues(),
				vista.profesionalId(), vista.profesionalNombre(), vista.turnoId(), vista.turnoInicio(),
				vista.personaId(), vista.personaNombre(), vista.cambioDeProfesional(), vista.turnoNuevoId(),
				CierreDeSedeEnHistorial.de(vista.cierreDeSede()), vista.operacionId(),
				vista.eventosRelacionados().stream().map(e -> new EventoRelacionadoDeAgenda(e.id(), e.ocurridoEn(),
						e.eventType(), e.turnoId(), e.consultorioId())).toList());
	}
}
