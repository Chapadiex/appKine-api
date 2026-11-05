package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.CambioDeAgendaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Ver {@link CambioDeAgendaView}. */
@Schema(
		name = "CambioDeAgenda",
		description = "Un cambio en la agenda de un profesional: horario habitual, excepcion o bloqueo, o un "
				+ "turno. `antes` y `despues` ya vienen armados para mostrar.")
public record CambioDeAgendaResponse(

		@Schema(description = "Id del hecho en la auditoria", example = "9812")
		long id,

		@Schema(example = "2026-11-04T15:20:00Z")
		Instant ocurridoEn,

		@Schema(description = "Que cambio", allowableValues = {"HORARIO_ALTA", "HORARIO_EDICION", "HORARIO_BAJA",
				"EXCEPCION_ALTA", "EXCEPCION_EDICION", "EXCEPCION_BAJA", "TURNO_RESERVA", "TURNO_SOBRETURNO",
				"TURNO_CONFIRMACION", "TURNO_CANCELACION", "TURNO_AUSENCIA", "TURNO_REPROGRAMACION",
				"TURNO_TRASLADO", "TURNO_OBSERVACIONES"}, example = "TURNO_REPROGRAMACION")
		String tipo,

		@Schema(description = "Sobre que es el cambio", allowableValues = {"HORARIO", "EXCEPCION", "TURNO"})
		String entidad,

		@Schema(description = "Id del bloque, de la excepcion o del turno", example = "4100")
		Long entidadId,

		@Schema(description = "Sede del cambio", example = "3")
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

		@Schema(description = "Membership del profesional en esa sede", example = "31")
		Long profesionalId,

		@Schema(example = "Gomez, Laura")
		String profesionalNombre,

		@Schema(description = "El turno, en los cambios de turno", example = "4100")
		Long turnoId,

		@Schema(description = "Horario del turno, en los cambios de turno", example = "2026-11-10T14:00:00Z")
		Instant turnoInicio,

		@Schema(description = "Paciente del turno. Solo si quien lee tiene `turno:read` en esa sede", example = "77")
		Long personaId,

		@Schema(description = "Nombre del paciente, con la misma condicion", example = "Perez, Juan")
		String personaNombre,

		@Schema(description = "`true` en una reprogramacion que paso el turno a otro profesional")
		boolean cambioDeProfesional) {

	public static CambioDeAgendaResponse de(CambioDeAgendaView vista) {
		return new CambioDeAgendaResponse(vista.id(), vista.ocurridoEn(), vista.tipo().name(), vista.entidad(),
				vista.entidadId(), vista.consultorioId(), vista.consultorioNombre(), vista.actorCuentaId(),
				vista.actorNombre(), vista.actorRol(), vista.motivo(), vista.antes(), vista.despues(),
				vista.profesionalId(), vista.profesionalNombre(), vista.turnoId(), vista.turnoInicio(),
				vista.personaId(), vista.personaNombre(), vista.cambioDeProfesional());
	}
}
