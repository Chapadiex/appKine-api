package com.akine.activity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Lo que manda quien inscribe a alguien en una clase.
 *
 * <p>{@code aceptaListaEspera} <b>se pide y no se asume</b>: encolar a quien queria un lugar, o
 * reservarle un lugar a quien pidio la cola, son las dos formas de que el mostrador termine
 * diciendole a una persona algo que no es cierto.
 */
@Schema(
		name = "Inscribir",
		description = "Reserva un cupo para una Persona. **La persona no se convierte en "
				+ "paciente**: anotarse en una clase no es entrar al circuito clinico.")
public record InscribirRequest(

		@Schema(description = "Persona del padron de la organizacion", example = "512")
		@Positive long personaId,

		@Schema(
				description = "Si no hay lugar: true la deja en la lista de espera, false "
						+ "responde clase-completa sin anotarla.",
				example = "true")
		boolean aceptaListaEspera,

		@Schema(
				description = "Clave del cliente para que un reintento no inscriba dos veces. "
						+ "Reusarla con otra persona es un 409, no un reintento.",
				example = "9f1c2b7e-inscripcion-512")
		@Size(max = 80) String idempotencyKey) {
}
