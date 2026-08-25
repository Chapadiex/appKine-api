package com.akine.resource.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Edicion parcial de un concepto del catalogo. Los campos omitidos no se modifican.
 *
 * <p><b>El codigo no se puede editar</b>, y el {@code alcance} tampoco. El primero es la clave
 * estable con la que los historicos referencian el concepto; el segundo haria visible para todo
 * el SaaS un concepto que nadie mas pidio. El camino para volver global un concepto propio es
 * una solicitud de alta ({@code POST /api/v1/catalogo-solicitudes}).
 *
 * <p>Para borrar la descripcion se manda cadena vacia. Para dejar la vigencia sin fin se manda
 * {@code clearValidUntil = true}.
 *
 * <p><b>Ningun componente es un primitivo:</b> Jackson 3 pasa {@code null} por cada componente
 * ausente de un record y {@code FAIL_ON_NULL_FOR_PRIMITIVES} lo rechaza con un 400 que no nombra
 * el campo. En un DTO de PATCH la mitad de los campos siempre viene ausente.
 */
@Schema(description = "Cambios a aplicar sobre un concepto del catalogo")
public record UpdateCatalogoConceptoRequest(

		@Schema(description = "Nuevo nombre visible",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String name,

		@Schema(description = "Nueva descripcion. Cadena vacia la borra",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La descripcion no puede superar los 280 caracteres")
		String descripcion,

		@Schema(description = "Nuevo inicio de la vigencia, en UTC",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Instant validFrom,

		@Schema(description = "Nuevo fin de la vigencia, EXCLUSIVO. Se ignora si "
				+ "clearValidUntil es true",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Instant validUntil,

		@Schema(
				description = "Deja el concepto sin fin de vigencia. Hace explicito lo que un "
						+ "campo omitido no puede expresar: 'no toques el fin' y 'sacale el fin' "
						+ "son dos intenciones distintas",
				example = "false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean clearValidUntil,

		@Schema(
				description = "Solo para una vigencia de nomenclador: corrige el valor publicado. "
						+ "Corregir NO es actualizar el nomenclador; actualizarlo es cerrar esta "
						+ "vigencia y abrir una nueva",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@PositiveOrZero(message = "El valor de referencia no puede ser negativo")
		BigDecimal valorReferencia,

		@Schema(
				description = "Version que el cliente leyo. Si quedo vieja, 409 "
						+ "concurrent-modification y hay que recargar",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version es obligatoria para editar un concepto")
		Long version) {
}
