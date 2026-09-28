package com.akine.activity.api.dto;

import com.akine.activity.application.ItemDeLote;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * El resultado de <b>un</b> participante dentro de un lote (RF-M13-008).
 *
 * <p>O trae el resultado, o trae el error de ese participante. Nunca los dos, nunca ninguno.
 *
 * <p>El {@code problemType} es <b>el mismo</b> que habria viajado en un Problem Detail si la
 * operacion hubiera sido individual, para que el cliente use el mapeo que ya tiene y no un segundo
 * vocabulario de errores.
 */
@Schema(
		name = "ItemDeLoteDeAsistencia",
		description = "Resultado individual. Un fallo aca no revierte los items anteriores: cada "
				+ "uno corre en su propia transaccion (RF-M13-008).")
public record ItemDeLoteResponse(

		@Schema(example = "412") long inscripcionId,

		@Schema(description = "Presente cuando el item salio bien")
		ResultadoDeAsistenciaResponse resultado,

		@Schema(
				description = "URI del tipo de problema, la misma del Problem Detail individual",
				example = "https://akine.app/problems/inscripcion-transicion-no-permitida")
		String problemType,

		@Schema(description = "Explicacion del fallo de este participante") String detalle) {

	public static ItemDeLoteResponse de(ItemDeLote item) {
		return new ItemDeLoteResponse(
				item.inscripcionId(),
				item.resultado() == null ? null : ResultadoDeAsistenciaResponse.de(item.resultado()),
				item.problemType(),
				item.detalle());
	}
}
