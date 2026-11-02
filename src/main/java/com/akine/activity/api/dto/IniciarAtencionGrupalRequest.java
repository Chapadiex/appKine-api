package com.akine.activity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Un lote de atenciones de una clase (AKINE-08.05). <b>Cada item va en su propia
 * transaccion</b>: un fallo individual no revierte ni oculta a los demas.
 */
@Schema(
		name = "IniciarAtencionGrupal",
		description = "Abre la Sesion de varios participantes. La respuesta es **200 aunque haya "
				+ "fallos parciales**.")
public record IniciarAtencionGrupalRequest(

		@Schema(description = "Hasta 200 participantes")
		@NotEmpty(message = "Los items son obligatorios")
		@Size(max = 200, message = "Los items no pueden ser mas de 200")
		List<@NotNull(message = "Los items no pueden tener valores vacios") @Valid Item> items) {

	@Schema(name = "IniciarAtencionGrupalItem", description = "Un participante del lote")
	public record Item(
			@Schema(example = "412") @Positive(message = "La inscripcion no es valida") long inscripcionId,
			@Schema(description = "Opcional si tiene una sola derivacion vigente", example = "88")
			@Positive(message = "La derivacion no es valida") Long derivacionId) {
	}
}
