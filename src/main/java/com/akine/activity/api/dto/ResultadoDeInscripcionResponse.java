package com.akine.activity.api.dto;

import com.akine.activity.application.ResultadoDeInscripcion;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * La inscripcion y como quedaron los cupos.
 *
 * <p>Los cupos viajan en la misma respuesta para que la pantalla no tenga que pedirlos aparte
 * justo despues de moverlos — que es ademas el momento en que mas rapido quedan viejos.
 */
@Schema(name = "ResultadoDeInscripcion")
public record ResultadoDeInscripcionResponse(
		InscripcionResponse inscripcion, CuposResponse cupos) {

	public static ResultadoDeInscripcionResponse de(ResultadoDeInscripcion resultado) {
		return new ResultadoDeInscripcionResponse(
				InscripcionResponse.de(resultado.inscripcion()),
				CuposResponse.de(resultado.cupos()));
	}
}
