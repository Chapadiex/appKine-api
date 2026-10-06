package com.akine.resource.api.dto;

import com.akine.resource.application.DisponibilidadView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Disponibilidad de un espacio para una ventana (RF-M04-003).
 *
 * <h2>Que responde, y que NO — leer antes de escribir la pantalla</h2>
 *
 * <p>Responde si el recurso esta en servicio para la ventana —existe, no esta dado de baja
 * (RN-M04-002) y su vigencia la cubre entera— y cuantos lugares tiene comprometidos.
 *
 * <p>{@code lugaresComprometidos} es el <b>pico</b> de turnos pendientes del espacio que se
 * cruzan en un mismo instante desde {@code desde} <b>en adelante</b>, sin acotar a
 * {@code hasta}: lo calcula {@code scheduling.infrastructure.EspacioOcupadoPorTurnos} (paquete
 * E-1). {@code lugaresDisponibles} es {@code capacidad} menos ese pico, y {@code disponible}
 * exige servicio en toda la ventana y al menos un lugar libre.
 *
 * <p>Es una cota conservadora: puede mostrar ocupado un box que en la franja puntual esta
 * libre, nunca al reves. Si una franja concreta se puede reservar lo responde la agenda, no
 * esta consulta. Las inscripciones a clases no se cuentan.
 */
@Schema(description = "Disponibilidad de un espacio para la ventana consultada")
public record EspacioAvailabilityResponse(

		@Schema(description = "Identificador del espacio", example = "1")
		long espacioId,

		@Schema(description = "Nombre operativo del espacio", example = "Box 1")
		String name,

		@Schema(description = "Clasificacion fisica del recurso", example = "BOX")
		String tipo,

		@Schema(description = "Capacidad configurada del espacio", example = "1")
		int capacidad,

		@Schema(description = "Inicio de la ventana consultada, en UTC",
				example = "2026-09-01T13:00:00Z")
		Instant desde,

		@Schema(description = "Fin de la ventana consultada, exclusivo, en UTC",
				example = "2026-09-01T14:00:00Z")
		Instant hasta,

		@Schema(description = "Si el espacio se puede ofrecer para esa ventana: esta en servicio "
				+ "durante toda la ventana y le queda al menos un lugar libre",
				example = "true")
		boolean disponible,

		@Schema(description = "Pico de turnos pendientes del espacio que se cruzan en un mismo "
				+ "instante, desde el inicio de la ventana en adelante (no solo dentro de ella). "
				+ "Es una cota conservadora", example = "0")
		long lugaresComprometidos,

		@Schema(description = "Lugares libres = capacidad - lugaresComprometidos", example = "1")
		long lugaresDisponibles) {

	public static EspacioAvailabilityResponse from(DisponibilidadView view) {
		return new EspacioAvailabilityResponse(
				view.espacioId(),
				view.name(),
				view.tipo(),
				view.capacidad(),
				view.desde(),
				view.hasta(),
				view.disponible(),
				view.lugaresComprometidos(),
				view.lugaresDisponibles());
	}
}
