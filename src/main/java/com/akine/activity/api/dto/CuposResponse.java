package com.akine.activity.api.dto;

import com.akine.activity.application.CuposView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Los cupos de una clase (RF-M12-010).
 *
 * <p><b>No lleva ningun dato de persona</b>, y por eso alcanza con {@code clase:read} para verla.
 * Que no haya nada que recortar despues es lo que la hace segura para una pantalla publica.
 */
@Schema(
		name = "Cupos",
		description = "Disponibilidad de una clase. Sostiene CA-M12-010-06: la agenda muestra 6/8 "
				+ "y admite exactamente dos confirmaciones adicionales.")
public record CuposResponse(

		@Schema(example = "77") long claseId,
		@Schema(description = "Cupo propio declarado al programar", example = "8") int capacidad,

		@Schema(
				description = "Minimo entre el cupo propio, el de la oferta y el del espacio "
						+ "(RN-M28-002). Se calcula al leer.",
				example = "8")
		int capacidadEfectiva,

		@Schema(description = "Lugares otorgados", example = "6") int ocupados,

		@Schema(
				description = "Lugares libres. **Nunca negativo**: si la capacidad efectiva bajo "
						+ "por debajo de lo ocupado, la respuesta correcta es 0.",
				example = "2")
		int disponibles,

		@Schema(description = "Cuantos esperan. **No consumen cupo** (RN-M28-005).", example = "3")
		int enEspera) {

	public static CuposResponse de(CuposView view) {
		return new CuposResponse(
				view.claseId(), view.capacidad(), view.capacidadEfectiva(),
				view.ocupados(), view.disponibles(), view.enEspera());
	}
}
