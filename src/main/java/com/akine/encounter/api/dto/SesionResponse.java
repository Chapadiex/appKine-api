package com.akine.encounter.api.dto;

import com.akine.encounter.application.SesionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Una atencion abierta. <b>No es un turno</b>: DP-05 los separa en maquinas de estado distintas. */
@Schema(name = "Sesion", description = "Atencion real, con su borrador y su version de autosave")
public record SesionResponse(

		@Schema(example = "501")
		long id,

		@Schema(example = "7")
		long consultorioId,

		@Schema(
				description = "Historia Clinica del paciente, de la ORGANIZACION y no de la sede "
						+ "(DP-03). Se crea al iniciar la primera atencion si no existia.",
				example = "88")
		long historiaClinicaId,

		@Schema(description = "Turno que origino la atencion. Ausente en una atencion sin turno.", example = "301")
		Long turnoId,

		@Schema(example = "42")
		long ofertaId,

		@Schema(description = "Membership del profesional que atiende. Decide quien puede guardar.", example = "31")
		long profesionalId,

		@Schema(description = "Estado de la ATENCION, no del turno ni del cobro", allowableValues = "BORRADOR", example = "BORRADOR")
		String estado,

		@Schema(example = "2026-09-15T12:02:44Z")
		Instant iniciadaEn,

		@Schema(description = "Contenido opaco tal como se guardo. Ausente si todavia no se guardo nada.")
		String borrador,

		@Schema(example = "2026-09-15T12:18:03Z")
		Instant borradorGuardadoEn,

		@Schema(description = "**Devolvela al guardar.** Es lo que impide que dos pestanas se pisen.", example = "3")
		long version) {

	public static SesionResponse de(SesionView vista) {
		return new SesionResponse(
				vista.id(), vista.consultorioId(), vista.historiaClinicaId(), vista.turnoId(),
				vista.ofertaId(), vista.profesionalId(), vista.estado(), vista.iniciadaEn(),
				vista.borrador(), vista.borradorGuardadoEn(), vista.version());
	}
}
