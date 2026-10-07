package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.SerieResumenView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Una fila de la bandeja de series (AKINE E-8): la regla resumida y la foto de sus turnos hoy.
 *
 * <p>No lleva la lista de turnos —para eso esta {@code verSerieDeTurnos}—, si el paciente
 * resuelto, con la misma PHI minima que la agenda del dia: nombre y documento, nada clinico.
 */
@Schema(
		name = "SerieDeTurnosResumen",
		description = "Una serie de turnos en la bandeja de la sede: regla resumida, paciente y "
				+ "oferta resueltos, y cuantos turnos le quedan. PHI minima: ningun dato clinico.")
public record SerieDeTurnosResumenResponse(

		@Schema(example = "12")
		long id,

		@Schema(example = "7")
		long consultorioId,

		@Schema(example = "128")
		long personaId,

		@Schema(description = "Apellido y nombre, ya compuestos", example = "Perez, Ana")
		String personaNombre,

		@Schema(description = "Tipo y numero, para desambiguar homonimos. Ausente si la ficha no lo tiene.", example = "DNI 30111222")
		String documento,

		@Schema(example = "42")
		long ofertaId,

		@Schema(example = "Kinesiologia - sesion individual")
		String ofertaNombre,

		@Schema(description = "Membership del profesional con que se genero. Ausente si la oferta no lo requiere.", example = "31")
		Long profesionalId,

		@Schema(allowableValues = {"SEMANAL"}, example = "SEMANAL")
		String frecuencia,

		@Schema(description = "Dias ISO, 1 = lunes", example = "[1, 4]")
		List<Integer> diasSemana,

		@Schema(description = "Hora local de inicio", type = "string", format = "time", example = "09:00:00")
		LocalTime hora,

		@Schema(example = "2026-10-12")
		LocalDate fechaDesde,

		@Schema(description = "Ausente si la serie termina por cantidad", example = "2026-12-21")
		LocalDate fechaHasta,

		@Schema(description = "Ausente si la serie termina por fecha", example = "10")
		Integer cantidad,

		@Schema(description = "Zona de la sede al crear la serie", example = "America/Argentina/Cordoba")
		String timezone,

		@Schema(example = "2026-10-06T14:03:11Z")
		Instant creadaEn,

		@Schema(description = "Turnos de la serie hoy, en cualquier estado", example = "10")
		int totalTurnos,

		@Schema(description = "Turnos RESERVADO o CONFIRMADO que todavia no empezaron", example = "6")
		int turnosPendientes,

		@Schema(description = "Inicio del proximo turno pendiente, UTC. Ausente si no queda ninguno.", example = "2026-10-19T12:00:00Z")
		Instant proximoTurnoInicio,

		// CUIDADO: lista escrita a mano, con las constantes de domain.EstadoDeSerie.
		@Schema(
				description = "Estado DERIVADO de sus turnos, calculado al leer: la serie no tiene "
						+ "estado propio (DP-04). `VIGENTE` si le queda un turno pendiente, "
						+ "`FINALIZADA` si no.",
				allowableValues = {"VIGENTE", "FINALIZADA"},
				example = "VIGENTE")
		String estado) {

	public static SerieDeTurnosResumenResponse de(SerieResumenView vista) {
		return new SerieDeTurnosResumenResponse(
				vista.id(), vista.consultorioId(), vista.personaId(), vista.personaNombre(),
				vista.documento(), vista.ofertaId(), vista.ofertaNombre(), vista.profesionalId(),
				vista.frecuencia(), vista.diasSemana(), vista.hora(), vista.fechaDesde(),
				vista.fechaHasta(), vista.cantidad(), vista.timezone(), vista.creadaEn(),
				vista.totalTurnos(), vista.turnosPendientes(), vista.proximoTurnoInicio(),
				vista.estado().name());
	}
}
