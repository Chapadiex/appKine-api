package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.TurnoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un turno reservado.
 *
 * <p>No lleva nombre ni documento del paciente, solo su id. Quien consulta un turno pide la ficha
 * por su propio endpoint; duplicarla aca la expondria en toda respuesta de agenda, incluida la de
 * un rol que puede ver turnos y no fichas.
 */
@Schema(name = "Turno", description = "Reserva de un slot. No es la prestacion: DP-05 separa Turno, Recepcion y Sesion.")
public record TurnoResponse(

		@Schema(example = "301")
		long id,

		@Schema(example = "7")
		long consultorioId,

		@Schema(example = "42")
		long ofertaId,

		@Schema(description = "Persona del padron para la que se reservo", example = "128")
		long personaId,

		@Schema(description = "Membership del profesional. Ausente si la oferta no lo requiere.", example = "31")
		Long profesionalId,

		@Schema(
				description = "Box asignado. **Lo elige el servidor al reservar**, no la busqueda: "
						+ "elegirlo fuera de la transaccion que crea el turno seria una promesa que "
						+ "dos busquedas concurrentes rompen. Ausente si la oferta no requiere espacio.",
				example = "55")
		Long espacioId,

		@Schema(description = "Inicio, UTC", example = "2026-09-15T12:00:00Z")
		Instant inicio,

		@Schema(description = "Fin, UTC y EXCLUSIVO. Congelado al reservar: si despues se edita la duracion de la oferta, este turno no se mueve.", example = "2026-09-15T12:45:00Z")
		Instant fin,

		@Schema(description = "Estado de la RESERVA. Nunca dice que la atencion ocurrio: eso lo dice la Sesion.", allowableValues = {"RESERVADO", "CONFIRMADO"}, example = "RESERVADO")
		String estado,

		@Schema(example = "2026-09-01T14:03:11Z")
		Instant reservadoEn,

		@Schema(description = "Ausente mientras el turno no se confirme", example = "2026-09-01T14:05:00Z")
		Instant confirmadoEn,

		@Schema(description = "Version para el control optimista de las mutaciones de 05.03", example = "0")
		long version) {

	public static TurnoResponse de(TurnoView vista) {
		return new TurnoResponse(
				vista.id(), vista.consultorioId(), vista.ofertaId(), vista.personaId(),
				vista.profesionalId(), vista.espacioId(), vista.inicio(), vista.fin(),
				vista.estado(), vista.reservadoEn(), vista.confirmadoEn(), vista.version());
	}
}
