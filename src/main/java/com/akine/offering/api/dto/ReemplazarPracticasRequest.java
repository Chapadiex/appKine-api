package com.akine.offering.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * El conjunto COMPLETO de practicas de una oferta, con su principal (A-9, DP-11).
 *
 * <p><b>Reemplaza, no agrega</b>, con el mismo criterio que las habilitaciones. Pero al reves que
 * ellas, <b>una lista vacia NO significa "todas"</b>: deja la oferta sin practicas declaradas.
 */
@Schema(description = "Conjunto completo de practicas de la oferta y su principal. Reemplaza, no agrega")
public record ReemplazarPracticasRequest(

		@Schema(
				description = "Ids de las practicas del catalogo (globales o propias del centro) que la "
						+ "oferta puede prestar. Lista vacia = la oferta no declara practicas; NO "
						+ "significa todas",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La lista de practicas es obligatoria; para no declarar ninguna, mandala vacia")
		List<Long> practicaIds,

		@Schema(
				description = "Practica principal: la que se devenga o consume cuando una sesion de "
						+ "esta oferta cierra sin tratamientos. Obligatoria y dentro de practicaIds si "
						+ "la lista no es vacia; null si es vacia",
				example = "51")
		Long practicaPrincipalId,

		@Schema(
				description = "Version de la OFERTA que el cliente cree estar configurando. Es la misma "
						+ "que usan los reemplazos de habilitaciones",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "expectedVersion es obligatorio")
		@Min(value = 0, message = "expectedVersion no puede ser negativo")
		Long expectedVersion) {
}
