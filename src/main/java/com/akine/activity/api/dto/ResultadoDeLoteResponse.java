package com.akine.activity.api.dto;

import com.akine.activity.application.ResultadoDeLote;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * El resultado completo de un lote de asistencia (RF-M13-008).
 *
 * <p><b>Viaja con 200 aunque haya fallos parciales</b>: no es un error del pedido, es el resultado
 * del pedido. Publicar un 207 obligaria a cada cliente generado a manejar un status que este
 * repositorio no usa en ninguna otra parte.
 */
@Schema(
		name = "ResultadoDeLoteDeAsistencia",
		description = "Resultados parciales explicitos: cada participante conserva su estado "
				+ "propio (CA-M13-008-06).")
public record ResultadoDeLoteResponse(

		List<ItemDeLoteResponse> items,
		@Schema(example = "38") int exitosos,
		@Schema(example = "2") int fallidos,
		CuposResponse cupos) {

	public static ResultadoDeLoteResponse de(ResultadoDeLote resultado) {
		return new ResultadoDeLoteResponse(
				resultado.items().stream().map(ItemDeLoteResponse::de).toList(),
				resultado.exitosos(),
				resultado.fallidos(),
				CuposResponse.de(resultado.cupos()));
	}
}
