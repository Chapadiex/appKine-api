package com.akine.resource.api.dto;

import com.akine.resource.application.ImpactoDeDisponibilidad;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Los turnos pendientes que un cambio de disponibilidad dejaria afuera, calculado sin aplicarlo
 * (A-11, RN-M05-004).
 */
@Schema(name = "ImpactoDisponibilidadResponse",
		description = "Turnos pendientes que el cambio dejaria fuera de la disponibilidad efectiva. "
				+ "Es el conjunto exacto dentro de la ventana evaluada: un turno que sigue "
				+ "cubierto por otro bloque, o que ya estaba fuera de la disponibilidad antes del "
				+ "cambio, no cuenta")
public record ImpactoDisponibilidadResponse(

		@Schema(description = "Cuantos turnos quedarian afuera. Es la cuenta completa aunque la "
				+ "lista venga recortada", example = "3")
		long turnosAfectados,

		@Schema(description = "Inicio del primero de esos turnos. Nulo si no hay ninguno",
				nullable = true)
		Instant primerTurnoAfectado,

		@Schema(description = "Los primeros 50 turnos afectados, por inicio. Sin datos del "
				+ "paciente: el detalle se abre en la agenda")
		List<TurnoAfectadoResponse> turnos,

		@Schema(description = "Fecha local EXCLUSIVA hasta la que se evaluo: el fin de vigencia "
				+ "del cambio, acotado a noventa dias. Nulo si el cambio no tenia ningun tramo "
				+ "futuro que evaluar", example = "2027-01-05", nullable = true)
		LocalDate evaluadoHasta) {

	@Schema(name = "TurnoAfectadoPorDisponibilidadResponse",
			description = "Un turno pendiente que el cambio dejaria afuera")
	public record TurnoAfectadoResponse(

			@Schema(description = "Id del turno", example = "41")
			long turnoId,

			@Schema(description = "Vinculo del profesional del turno", example = "7")
			long membershipId,

			@Schema(description = "Inicio del turno")
			Instant inicio,

			@Schema(description = "Fin del turno, exclusivo")
			Instant fin) {
	}

	public static ImpactoDisponibilidadResponse from(ImpactoDeDisponibilidad impacto) {
		return new ImpactoDisponibilidadResponse(
				impacto.turnosAfectados(),
				impacto.primerTurnoAfectado(),
				impacto.turnos().stream()
						.map(turno -> new TurnoAfectadoResponse(
								turno.turnoId(), turno.membershipId(), turno.inicio(), turno.fin()))
						.toList(),
				impacto.evaluadoHasta());
	}
}
