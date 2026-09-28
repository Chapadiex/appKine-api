package com.akine.activity.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/**
 * Lo que manda quien reprograma una clase.
 *
 * <p><b>Lleva la version leida.</b> Sin ella, dos operadores que abrieron la misma clase se pisan
 * y el ultimo gana en silencio.
 */
@Schema(
		name = "ReprogramarClase",
		description = "Mueve la clase conservando su identidad y sus participantes. No la recrea: "
				+ "las inscripciones cuelgan de su id.")
public record ReprogramarClaseRequest(

		@Schema(description = "Nuevo instante de inicio, UTC", example = "2026-10-06T12:00:00Z")
		@NotNull Instant inicio,

		@Schema(description = "Nuevo instante de fin, UTC y exclusivo", example = "2026-10-06T13:00:00Z")
		@NotNull Instant fin,

		@Schema(description = "Membership del profesional. Obligatorio si la oferta lo requiere.", example = "31")
		@Positive Long profesionalId,

		@Schema(
				description = "Nuevo cupo. **No puede quedar por debajo de los participantes ya "
						+ "confirmados** (RF-M12-012).",
				example = "10")
		@NotNull @Min(2) Integer capacidad,

		@Schema(description = "Version leida de la clase, para el control optimista", example = "3")
		@NotNull @PositiveOrZero Long version) {
}
