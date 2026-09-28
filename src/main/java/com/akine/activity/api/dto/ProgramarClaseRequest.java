package com.akine.activity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Lo que manda quien programa una clase.
 *
 * <p>Lleva {@code inicio} y {@code fin}, no una duracion derivada de la oferta: una clase de
 * Pilates de hora y media sobre una oferta de 60 minutos es un caso real, y derivarlo obligaria a
 * crear una oferta por cada duracion.
 */
@Schema(
		name = "ProgramarClase",
		description = "Programa un evento grupal unico. NO crea un turno por participante: la "
				+ "clase existe una sola vez cualquiera sea el numero de inscriptos.")
public record ProgramarClaseRequest(

		@Schema(description = "Instante de inicio, UTC", example = "2026-10-05T12:00:00Z")
		@NotNull Instant inicio,

		@Schema(description = "Instante de fin, UTC y exclusivo", example = "2026-10-05T13:00:00Z")
		@NotNull Instant fin,

		@Schema(
				description = "Membership del profesional o instructor. Obligatorio si la oferta lo requiere.",
				example = "31")
		@Positive Long profesionalId,

		@Schema(
				description = "Cupo propio de la clase. Queda ademas limitado por la capacidad de la "
						+ "oferta y la del espacio (RN-M28-002). **Minimo 2**: una clase de "
						+ "capacidad 1 es un turno individual mal rotulado.",
				example = "8")
		@NotNull @Min(2) Integer capacidad,

		@Schema(
				description = "Rotulo de esta ocurrencia. El nombre comercial lo da la oferta.",
				example = "Pilates - grupo avanzado")
		@Size(max = 120) String titulo,

		@Schema(
				description = "Clave del cliente para que un reintento no programe dos clases. "
						+ "Reusarla con **otro** contenido devuelve 409 `idempotency-key-conflict`, "
						+ "no la clase anterior.",
				example = "b3f1c2a4-5d6e-4f70-8a91-2c3d4e5f6a7b")
		@Size(max = 80) String idempotencyKey) {
}
