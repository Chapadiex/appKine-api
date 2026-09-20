package com.akine.activity.api.dto;

import com.akine.activity.application.ResultadoDeAsistencia;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Como termino un registro de asistencia.
 *
 * <p>Los tres desenlaces viajan explicitos en vez de deducirse de comparar campos, porque la
 * pantalla los muestra distinto: se marco, se corrigio, o el pedido repetia lo ya registrado y no
 * se escribio nada.
 */
@Schema(
		name = "ResultadoDeAsistencia",
		description = "La asistencia y como quedo la inscripcion. `registrada` y `corregida` en "
				+ "false significa que el pedido era un reintento y no cambio nada.")
public record ResultadoDeAsistenciaResponse(

		AsistenciaResponse asistencia,

		@Schema(description = "La reserva, con su estado ya proyectado a ASISTIO o AUSENTE")
		InscripcionResponse inscripcion,

		@Schema(description = "true si esta llamada creo el hecho", example = "true")
		boolean registrada,

		@Schema(description = "true si esta llamada corrigio un hecho previo", example = "false")
		boolean corregida) {

	public static ResultadoDeAsistenciaResponse de(ResultadoDeAsistencia resultado) {
		return new ResultadoDeAsistenciaResponse(
				AsistenciaResponse.de(resultado.asistencia()),
				InscripcionResponse.de(resultado.inscripcion()),
				resultado.registrada(),
				resultado.corregida());
	}
}
