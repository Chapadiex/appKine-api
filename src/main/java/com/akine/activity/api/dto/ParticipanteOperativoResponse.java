package com.akine.activity.api.dto;

import com.akine.activity.application.ParticipanteOperativoView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Una fila del detalle operativo: quien es, como esta su reserva y como resulto su participacion.
 *
 * <p>Nombre y documento, nada mas. Ni correo, ni telefono, ni un solo dato clinico o economico:
 * para pasar lista alcanza con saber a quien se esta llamando (RNF-M28-002).
 */
@Schema(
		name = "ParticipanteOperativo",
		description = "Inscripcion mas asistencia. `asistencia` es null mientras nadie la marque.")
public record ParticipanteOperativoResponse(

		InscripcionResponse inscripcion,

		@Schema(description = "null si todavia nadie la marco") AsistenciaResponse asistencia,

		String apellido,
		String nombre,
		String tipoDocumento,
		String numeroDocumento) {

	public static ParticipanteOperativoResponse de(ParticipanteOperativoView view) {
		return new ParticipanteOperativoResponse(
				InscripcionResponse.de(view.inscripcion()),
				view.asistencia() == null ? null : AsistenciaResponse.de(view.asistencia()),
				view.apellido(), view.nombre(), view.tipoDocumento(), view.numeroDocumento());
	}
}
