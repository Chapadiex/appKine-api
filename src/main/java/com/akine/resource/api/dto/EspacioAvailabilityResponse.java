package com.akine.resource.api.dto;

import com.akine.resource.application.DisponibilidadView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Disponibilidad de un espacio para una ventana (RF-M04-003).
 *
 * <h2>Que responde hoy, y que NO — leer antes de escribir la pantalla</h2>
 *
 * <p>Responde <b>si el recurso esta en servicio</b> para la ventana: existe, no esta dado de
 * baja (RN-M04-002) y su vigencia la cubre entera.
 *
 * <p><b>NO responde si el recurso esta libre de reservas.</b> Los turnos son del modulo de
 * agenda y las inscripciones del de actividades; ninguno de los dos existe todavia. Por eso
 * {@code lugaresComprometidos} es <b>siempre 0</b> en esta version del contrato y
 * {@code lugaresDisponibles} es siempre igual a {@code capacidad}.
 *
 * <p>Cuando la agenda exista, esos dos numeros van a cambiar solos y este contrato no cambia:
 * un cliente escrito hoy sigue funcionando. Lo que NO hay que hacer es rotular
 * {@code disponible = true} como "el box esta libre": eso va a ser mentira en cuanto exista la
 * agenda, y el bug no va a parecer de esta etapa.
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

		@Schema(description = "Si el espacio se puede ofrecer para esa ventana. Hoy equivale a "
				+ "estar en servicio: la ocupacion real llega con el modulo de agenda",
				example = "true")
		boolean disponible,

		@Schema(description = "Lugares ya comprometidos en la ventana. SIEMPRE 0 en esta version "
				+ "del contrato: no existe todavia ningun modulo que reserve", example = "0")
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
