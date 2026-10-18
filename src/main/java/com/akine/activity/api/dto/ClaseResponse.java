package com.akine.activity.api.dto;

import com.akine.activity.application.ClaseView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Una clase, tal como la ve el cliente.
 *
 * <p><b>No lleva participantes.</b> La seguridad de la etapa exige vista sin lista de inscriptos, y
 * en 08.01 todavia no existen: la decision queda escrita para que 08.02 no la agregue por inercia
 * a esta respuesta.
 */
@Schema(name = "Clase", description = "Evento grupal unico de agenda (M28).")
public record ClaseResponse(

		@Schema(example = "77") long id,
		@Schema(example = "12") long consultorioId,
		@Schema(example = "45") long ofertaId,
		@Schema(example = "Pilates - grupo avanzado") String titulo,
		@Schema(example = "2026-10-05T12:00:00Z") Instant inicio,
		@Schema(example = "2026-10-05T13:00:00Z") Instant fin,
		@Schema(description = "PROGRAMADA o CANCELADA", example = "PROGRAMADA") String estado,
		@Schema(example = "31") Long profesionalId,
		@Schema(example = "8") Long espacioId,

		@Schema(description = "Cupo propio declarado al programar", example = "8")
		int capacidad,

		@Schema(
				description = "Minimo entre el cupo propio, el de la oferta y el del espacio "
						+ "(RN-M28-002). **Se calcula al leer**: si el box cambia de capacidad, "
						+ "este numero cambia con el.",
				example = "8")
		int capacidadEfectiva,

		@Schema(
				description = "Lugares tomados por inscripciones. Desde AKINE-08.02 es el numero "
						+ "real: sale de la columna que **otorga** el lugar, no de un conteo sobre "
						+ "las inscripciones.",
				example = "6")
		int ocupados,

		@Schema(example = "El instructor se reporto enfermo") String motivoCancelacion,
		Instant canceladoEn,

		@Schema(description = "Se manda de vuelta al reprogramar", example = "3") long version,

		@Schema(
				description = "Nombre comercial de la oferta, leido al responder. Ausente si la "
						+ "oferta ya no se puede resolver.",
				example = "Pilates reformer")
		String ofertaNombre,

		@Schema(
				description = "Nombre del profesional o instructor, leido al responder. Ausente si "
						+ "la clase no tiene profesional o su vinculo ya no se puede resolver.",
				example = "Laura Gomez")
		String profesionalNombre,

		@Schema(
				description = "Nombre del box, leido al responder. Ausente si la clase no tiene box "
						+ "o ya no se puede resolver.",
				example = "Salon 1")
		String espacioNombre) {

	public static ClaseResponse de(ClaseView view) {
		return new ClaseResponse(
				view.id(),
				view.consultorioId(),
				view.ofertaId(),
				view.titulo(),
				view.inicio(),
				view.fin(),
				view.estado(),
				view.profesionalId(),
				view.espacioId(),
				view.capacidad(),
				view.capacidadEfectiva(),
				view.ocupados(),
				view.motivoCancelacion(),
				view.canceladoEn(),
				view.version(),
				view.ofertaNombre(),
				view.profesionalNombre(),
				view.espacioNombre());
	}
}
