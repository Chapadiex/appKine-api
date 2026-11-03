package com.akine.scheduling.api.dto;

import com.akine.scheduling.domain.OrigenDeCancelacion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Varios turnos de la sede a cancelar con un mismo motivo. */
@Schema(
		name = "CancelarTurnosEnLote",
		description = "Turnos a cancelar con un mismo motivo, cada uno con la version que se leyo.")
public record CancelarTurnosEnLoteRequest(

		@Schema(description = "Los turnos, de 1 a 200")
		@NotEmpty(message = "Los items del lote son obligatorios")
		@Size(max = 200, message = "Los items del lote no pueden superar los 200")
		List<@Valid @NotNull(message = "Un item del lote esta vacio") Item> items,

		@Schema(description = "Por que se cancelan. **Obligatorio** (DP-04).", example = "Licencia del profesional")
		@NotBlank(message = "El motivo de cancelacion es obligatorio")
		@Size(max = 300, message = "El motivo no puede superar los 300 caracteres")
		String motivo,

		@Schema(description = "A quien se le atribuye la cancelacion. Opcional.",
				allowableValues = {"PACIENTE", "PROFESIONAL", "INSTITUCION"}, example = "PROFESIONAL")
		OrigenDeCancelacion origen) {

	@Schema(name = "TurnoACancelarEnLote", description = "Un turno del lote")
	public record Item(

			@Schema(description = "Id del turno", example = "4100")
			@NotNull(message = "El turno es obligatorio")
			@Positive(message = "El turno no es valido")
			Long turnoId,

			@Schema(description = "Version que devolvio la ultima lectura del turno", example = "0")
			@NotNull(message = "La version esperada es obligatoria")
			@Min(value = 0, message = "La version esperada no puede ser negativa")
			Long expectedVersion) {
	}
}
