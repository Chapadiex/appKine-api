package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * Lo que manda quien reserva un turno.
 *
 * <p>El cuerpo lleva el {@code inicio} y no un identificador de slot porque <b>los slots no
 * existen como fila</b>: se calculan al leer y se descartan. Lo unico que el cliente puede
 * devolverle al servidor es el instante que eligio, y el servidor lo revalida entero contra la
 * disponibilidad del momento.
 */
@Schema(
		name = "ReservarTurno",
		description = "Reserva de un slot. El servidor revalida que el hueco siga existiendo: "
				+ "entre que la pantalla mostro la agenda y el usuario confirmo pudo cambiar el "
				+ "horario del profesional, entrar un feriado o vencer la oferta.")
public record ReservarTurnoRequest(

		@Schema(description = "Persona del padron, que debe tener perfil de paciente vigente", example = "128")
		@NotNull @Positive Long personaId,

		@Schema(
				description = "Instante de inicio del slot elegido, UTC, tal como lo devolvio la agenda",
				example = "2026-09-15T12:00:00Z")
		@NotNull Instant inicio,

		@Schema(
				description = "Membership del profesional. Obligatorio si la oferta lo requiere.",
				example = "31")
		@Positive Long profesionalId,

		@Schema(
				description = "Clave del cliente para que un reintento no cree un segundo turno. "
						+ "Reusarla con **otro** contenido devuelve 409 `idempotency-key-conflict`, "
						+ "no el turno anterior. Omitirla desactiva la proteccion, que es legitimo "
						+ "en una carga manual.",
				example = "b3f1c2a4-5d6e-4f70-8a91-2c3d4e5f6a7b")
		@Size(max = 80) String idempotencyKey) {
}
