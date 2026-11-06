package com.akine.resource.api.dto;

import com.akine.resource.application.BloqueView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Resultado del alta en lote de bloques de disponibilidad.
 *
 * <p>Lleva los bloques —uno por dia pedido, en orden de dia— y las dos cuentas que la pantalla
 * necesita para redactar el aviso: cuantos se crearon y cuantos ya existian. Un dia cuyo bloque ya
 * existia identico no es un error ni un duplicado: es el reintento de red del alta suelta, aplicado
 * dia por dia.
 */
@Schema(description = "Bloques de disponibilidad resultantes de un alta en lote")
public record BloquesCreadosResponse(

		@ArraySchema(arraySchema = @Schema(
				description = "Un bloque por dia pedido, en orden de dia de la semana"))
		List<BloqueResponse> bloques,

		@Schema(description = "Cuantos bloques CREO este pedido", example = "5")
		int creados,

		@Schema(description = "Cuantos bloques ya existian identicos y se devolvieron sin crear "
				+ "nada ni volver a auditar", example = "0")
		int existentes) {

	public static BloquesCreadosResponse from(List<BloqueView> vistas) {
		int creados = (int) vistas.stream().filter(BloqueView::nuevo).count();
		return new BloquesCreadosResponse(
				vistas.stream().map(BloqueResponse::from).toList(),
				creados,
				vistas.size() - creados);
	}
}
