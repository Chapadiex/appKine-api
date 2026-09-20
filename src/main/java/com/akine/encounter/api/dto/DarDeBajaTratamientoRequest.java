package com.akine.encounter.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * La baja logica de una intervencion registrada por error.
 *
 * <p><b>El motivo es obligatorio</b> y no es burocracia: es el mismo un dato clinico —"se
 * suspendio la electroterapia porque el paciente refirio molestia"— y sin el la auditoria no
 * responde por que seis meses despues.
 *
 * <p>La fila <b>no se borra</b> (regla maestra 10) y su {@code orden} no se libera.
 */
@Schema(
		name = "DarDeBajaTratamiento",
		description = "Baja LOGICA de una intervencion. La fila no se borra y su orden no se "
				+ "reutiliza.")
public record DarDeBajaTratamientoRequest(

		@Schema(
				description = "Por que se da de baja. Obligatorio.",
				example = "Se cargo en la sesion equivocada")
		@NotBlank @Size(max = 280) String motivo,

		@Schema(
				description = "Version de la **SESION** que el cliente leyo.",
				example = "3")
		@NotNull @PositiveOrZero Long version) {
}
