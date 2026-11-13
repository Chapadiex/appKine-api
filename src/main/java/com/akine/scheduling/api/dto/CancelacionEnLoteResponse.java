package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.CancelacionEnLoteService.ResultadoDeItem;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** El resultado de una cancelacion en lote, item por item. */
@Schema(
		name = "CancelacionEnLote",
		description = "Que paso con cada turno del lote. Un rechazo no deshace los demas.")
public record CancelacionEnLoteResponse(

		@Schema(description = "Cuantos quedaron cancelados, por este pedido o porque ya lo estaban", example = "4")
		int cancelados,

		@Schema(description = "Cuantos se rechazaron", example = "1")
		int rechazados,

		@Schema(description = "Un resultado por turno, en el orden del pedido; un id repetido aparece una vez")
		List<ResultadoResponse> resultados) {

	@Schema(name = "ResultadoDeCancelacionEnLote", description = "Lo que paso con un turno del lote")
	public record ResultadoResponse(

			@Schema(example = "4100")
			long turnoId,

			@Schema(
					description = "CANCELADO: lo cancelo este pedido. YA_CANCELADO: ya estaba cancelado (un "
							+ "reintento del mismo lote). RECHAZADO: no se cancelo; ver problemType.",
					allowableValues = {"CANCELADO", "YA_CANCELADO", "RECHAZADO"},
					example = "CANCELADO")
			String resultado,

			@Schema(description = "Solo en RECHAZADO: el type que habria recibido la cancelacion suelta "
					+ "(not-found, forbidden, turno-con-atencion, turno-transicion-no-permitida, "
					+ "concurrent-modification, validation-error)")
			String problemType,

			@Schema(description = "Solo en RECHAZADO: explicacion legible")
			String detalle,

			@Schema(description = "El turno despues del intento. Ausente si no existe en la sede o no es accesible")
			TurnoResponse turno) {
	}

	public static CancelacionEnLoteResponse de(List<ResultadoDeItem> resultados) {
		int rechazados = (int) resultados.stream().filter(r -> !r.ok()).count();
		return new CancelacionEnLoteResponse(resultados.size() - rechazados, rechazados,
				resultados.stream()
						.map(r -> new ResultadoResponse(r.turnoId(), r.resultado(), r.problemType(), r.detalle(),
								r.turno() == null ? null : TurnoResponse.de(r.turno())))
						.toList());
	}
}
