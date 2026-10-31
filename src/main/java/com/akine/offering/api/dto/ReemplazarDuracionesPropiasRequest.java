package com.akine.offering.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.List;

/**
 * El conjunto COMPLETO de duraciones propias de una oferta. Reemplaza, no agrega, igual que
 * {@link ReemplazarHabilitacionesRequest}: quien no viene vuelve a la duracion de la oferta.
 */
@Schema(description = "Conjunto completo de duraciones propias de una oferta. Reemplaza, no agrega")
public record ReemplazarDuracionesPropiasRequest(

		@Schema(
				description = "Las duraciones que quedan. Quien no viene vuelve a la duracion de la "
						+ "oferta. Lista vacia = nadie tiene duracion propia",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La lista de duraciones es obligatoria; para quitarlas todas, mandala vacia")
		List<@Valid @NotNull DuracionPropiaPedida> duraciones,

		@Schema(
				description = "Version de la OFERTA que el cliente cree estar configurando: la misma "
						+ "que usan los reemplazos de habilitaciones",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "expectedVersion es obligatorio")
		@Min(value = 0, message = "expectedVersion no puede ser negativo")
		Long expectedVersion) {

	/** Cuanto tarda un profesional. */
	@Schema(description = "Cuanto tarda un profesional en esta oferta")
	public record DuracionPropiaPedida(

			@Schema(description = "Vinculo del profesional con la organizacion", example = "215",
					requiredMode = Schema.RequiredMode.REQUIRED)
			@NotNull(message = "membershipId es obligatorio")
			Long membershipId,

			@Schema(description = "Duracion de un turno con este profesional, en minutos. Entre 1 y 1440",
					example = "30", requiredMode = Schema.RequiredMode.REQUIRED)
			@NotNull(message = "La duracion es obligatoria; para usar la de la oferta, quita la fila")
			@Positive(message = "La duracion tiene que ser mayor a cero")
			Integer duracionMinutos) {
	}
}
