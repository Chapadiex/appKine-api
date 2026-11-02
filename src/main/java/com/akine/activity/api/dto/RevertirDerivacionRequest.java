package com.akine.activity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** El motivo es obligatorio: deshacer un vinculo clinico sin decir por que no es auditable. */
@Schema(
		name = "RevertirDerivacion",
		description = "Deshace el vinculo con motivo. La fila queda: los historicos permanecen "
				+ "consultables. Revertir dos veces devuelve la misma derivacion.")
public record RevertirDerivacionRequest(

		@Schema(description = "Por que se deshace el vinculo",
				example = "Se derivo al caso equivocado")
		@NotBlank(message = "El motivo es obligatorio")
		@Size(max = 300, message = "El motivo no puede superar los 300 caracteres") String motivo) {
}
