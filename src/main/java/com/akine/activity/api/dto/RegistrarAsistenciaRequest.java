package com.akine.activity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Lo que manda quien marca a un participante (RF-M28-007).
 *
 * <p><b>No lleva clave de idempotencia y no es un olvido</b>: la clave natural del hecho es
 * (clase, persona) y ya vive en un unique. Repetir el mismo resultado no escribe nada; mandar otro
 * es una correccion, y ahi el motivo pasa a ser obligatorio.
 */
@Schema(
		name = "RegistrarAsistencia",
		description = "Marca a un participante. Repetir el mismo resultado es idempotente y "
				+ "devuelve 200 sin escribir; mandar otro resultado es una **correccion** y exige "
				+ "`motivo`.")
public record RegistrarAsistenciaRequest(

		@Schema(description = "La inscripcion del participante, no la persona", example = "412")
		@NotNull @Positive Long inscripcionId,

		@Schema(
				description = "`PRESENTE`, `PRESENTE_TARDE` o `AUSENTE`. **`TARDE` no es un estado "
						+ "de inscripcion**: RN-M28-004 enumera seis y el matiz operativo vive en "
						+ "el hecho. Los dos presentes dejan la inscripcion en `ASISTIO`.",
				example = "PRESENTE")
		@NotNull ResultadoAsistenciaApi resultado,

		@Schema(
				description = "Nota **operativa, no clinica**. La ve cualquiera con "
						+ "`inscripcion:read`, asi que no puede llevar informacion de salud.",
				example = "Llego a los 10 minutos")
		@Size(max = 500) String observaciones,

		@Schema(
				description = "Obligatorio **solo para corregir**. Registrar por primera vez no "
						+ "necesita explicacion; cambiar un hecho ya afirmado si.",
				example = "Se habia marcado ausente por error")
		@Size(max = 300) String motivo) {
}
