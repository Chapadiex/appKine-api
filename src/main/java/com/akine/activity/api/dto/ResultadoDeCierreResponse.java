package com.akine.activity.api.dto;

import com.akine.activity.application.ResultadoDeCierre;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * El resultado del cierre operativo de una clase.
 *
 * <p><b>Cerrar no cobra y no devenga nada</b>, misma regla que DP-06 le fijo al cierre de Sesion.
 * Lo que deja es una participacion resuelta por persona, que es la referencia unica de la que
 * 08.06 y 08.07 van a colgar el devengo cuando su politica exista.
 */
@Schema(
		name = "ResultadoDeCierreDeClase",
		description = "Idempotente: un segundo cierre devuelve `cerroAhora: false` y no crea una "
				+ "segunda ausencia para nadie.")
public record ResultadoDeCierreResponse(

		ClaseResponse clase,

		@Schema(description = "false si la clase ya estaba realizada", example = "true")
		boolean cerroAhora,

		@Schema(description = "Los que quedaron sin marcar y el cierre resolvio como ausentes")
		List<AsistenciaResponse> ausentados,

		@Schema(
				description = "Cuantos quedaron en la cola de una clase que ya ocurrio y se "
						+ "cancelaron. **No libera cupo**: quien espera nunca lo tuvo.",
				example = "1")
		int esperaCancelada,

		CuposResponse cupos) {

	public static ResultadoDeCierreResponse de(ResultadoDeCierre resultado) {
		return new ResultadoDeCierreResponse(
				ClaseResponse.de(resultado.clase()),
				resultado.cerroAhora(),
				resultado.ausentados().stream().map(AsistenciaResponse::de).toList(),
				resultado.esperaCancelada(),
				CuposResponse.de(resultado.cupos()));
	}
}
