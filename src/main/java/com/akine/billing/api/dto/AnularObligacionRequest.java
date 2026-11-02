package com.akine.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** La anulacion de una deuda. El motivo NO es opcional: ver su descripcion. */
@Schema(name = "AnularObligacion", description = "Anulacion de deuda con motivo obligatorio")
public record AnularObligacionRequest(

		@Schema(
				description = "**Obligatorio.** A diferencia de otras bajas del sistema, aca el "
						+ "motivo no se puede omitir: una deuda que se borra sin explicacion es "
						+ "exactamente lo que una auditoria busca, porque alguien anulo un cargo y "
						+ "no hay forma de saber si fue un error de carga, una cortesia u otra cosa.",
				example = "Cargada por error: la sesion se cerro con la oferta equivocada")
		@NotBlank(message = "El motivo es obligatorio")
		@Size(max = 280, message = "El motivo no puede superar los 280 caracteres") String motivo,

		@Schema(description = "Version que el cliente leyo", example = "0")
		@NotNull(message = "La version es obligatoria")
		@PositiveOrZero(message = "La version no puede ser negativa") Long version) {
}
