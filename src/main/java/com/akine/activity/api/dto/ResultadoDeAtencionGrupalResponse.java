package com.akine.activity.api.dto;

import com.akine.activity.application.ItemDeAtencionGrupal;
import com.akine.activity.application.ResultadoDeAtencionGrupal;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * El resultado de un lote de atencion grupal (AKINE-08.05). Viaja con 200 aunque haya fallos
 * parciales, como el lote de asistencia.
 */
@Schema(
		name = "ResultadoDeAtencionGrupal",
		description = "Resultados parciales explicitos: cada participante conserva su resultado "
				+ "propio y un fallo no oculta a los demas.")
public record ResultadoDeAtencionGrupalResponse(
		List<Item> items,
		@Schema(example = "6") int exitosos,
		@Schema(example = "1") int fallidos) {

	@Schema(
			name = "ItemDeAtencionGrupal",
			description = "O trae la atencion, o trae el error de ese participante")
	public record Item(
			@Schema(example = "412") long inscripcionId,
			@Schema(description = "Presente cuando el item salio bien")
			AtencionDeParticipanteResponse atencion,
			@Schema(
					description = "URI del tipo de problema, la misma del Problem Detail individual",
					example = "https://akine.app/problems/participacion-sin-derivacion")
			String problemType,
			@Schema(description = "Explicacion del fallo de este participante") String detalle) {

		static Item de(ItemDeAtencionGrupal item) {
			return new Item(
					item.inscripcionId(),
					item.atencion() == null ? null
							: AtencionDeParticipanteResponse.de(item.inscripcionId(), item.atencion()),
					item.problemType(),
					item.detalle());
		}
	}

	public static ResultadoDeAtencionGrupalResponse de(ResultadoDeAtencionGrupal resultado) {
		return new ResultadoDeAtencionGrupalResponse(
				resultado.items().stream().map(Item::de).toList(),
				resultado.exitosos(),
				resultado.fallidos());
	}
}
