package com.akine.activity.api.dto;

import com.akine.activity.application.ParticipanteView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Un participante con su identidad.
 *
 * <p><b>Es la unica respuesta de M28 que lleva datos de persona</b>, y por eso la unica detras de
 * {@code inscripcion:read}. La clase y sus cupos siguen sin nombrar a nadie.
 *
 * <p>Nombre y documento, nada mas: ni correo, ni telefono, ni un solo dato clinico. Para pasar
 * lista alcanza con saber a quien se esta llamando.
 */
@Schema(name = "Participante", description = "Un inscripto con su identidad, para pasar lista.")
public record ParticipanteResponse(

		InscripcionResponse inscripcion,
		@Schema(example = "Perez") String apellido,
		@Schema(example = "Ana") String nombre,
		@Schema(example = "DNI") String tipoDocumento,
		@Schema(example = "30111222") String numeroDocumento) {

	public static ParticipanteResponse de(ParticipanteView view) {
		return new ParticipanteResponse(
				InscripcionResponse.de(view.inscripcion()),
				view.apellido(),
				view.nombre(),
				view.tipoDocumento(),
				view.numeroDocumento());
	}
}
