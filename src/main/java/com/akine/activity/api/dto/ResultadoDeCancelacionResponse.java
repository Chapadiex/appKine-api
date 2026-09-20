package com.akine.activity.api.dto;

import com.akine.activity.application.ResultadoDeCancelacion;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * La inscripcion cancelada y, si la hubo, la que entro en su lugar.
 *
 * <p><b>La promocion viaja en la respuesta a proposito.</b> Quien dio la baja en el mostrador es
 * quien tiene delante el telefono de la persona que acaba de entrar, y descubrirlo recargando la
 * lista es descubrirlo tarde. El aviso automatico sale igual por el outbox; esto es para el humano
 * que esta atendiendo.
 */
@Schema(name = "ResultadoDeCancelacion")
public record ResultadoDeCancelacionResponse(

		InscripcionResponse inscripcion,

		@Schema(description = "Quien entro desde la lista de espera, o null si no habia nadie "
				+ "esperando o el lugar ya no existia")
		InscripcionResponse promovida,

		CuposResponse cupos) {

	public static ResultadoDeCancelacionResponse de(ResultadoDeCancelacion resultado) {
		return new ResultadoDeCancelacionResponse(
				InscripcionResponse.de(resultado.inscripcion()),
				resultado.promovida() == null ? null : InscripcionResponse.de(resultado.promovida()),
				CuposResponse.de(resultado.cupos()));
	}
}
