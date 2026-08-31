package com.akine.scheduling.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Los turnos disponibles de una oferta en una ventana, dia por dia.
 *
 * <p><b>Ningun dia de la ventana se omite.</b> La pantalla dibuja una grilla de fechas, y un dia
 * ausente la deja en blanco sin poder distinguir "no hay turnos" de "no pregunte por ese dia". El
 * dia sin slots viaja con su motivo.
 */
@Schema(
		name = "Agenda",
		description = "Turnos disponibles de una oferta en una ventana de fechas. Los slots se "
				+ "calculan al leer y no se persisten: un slot devuelto no es una reserva ni la "
				+ "garantiza.")
public record AgendaResponse(

		@Schema(description = "Oferta consultada", example = "42")
		long ofertaId,

		@Schema(description = "Sede de la oferta", example = "7")
		long consultorioId,

		@Schema(description = "Nombre comercial de la oferta", example = "Kinesiologia deportiva")
		String nombreComercial,

		@Schema(description = "Duracion de cada turno, en minutos", example = "45")
		int duracionMinutos,

		@Schema(
				description = "Zona horaria de la sede con la que se convirtieron los instantes. "
						+ "Viaja para que la pantalla pueda rotularla y para que un error de huso "
						+ "se vea en la respuesta en vez de deducirse de horarios corridos.",
				example = "America/Argentina/Cordoba")
		String timezone,

		@Schema(description = "Una entrada por cada fecha de la ventana, en orden ascendente")
		List<DiaResponse> dias) {

	@Schema(name = "DiaDeAgenda", description = "Un dia resuelto: o tiene slots, o tiene motivo")
	public record DiaResponse(

			@Schema(description = "Fecha local de la sede", example = "2026-09-15")
			LocalDate fecha,

			@Schema(
					description = "Por que el dia no tiene ningun slot. Ausente si el dia SI tiene "
							+ "slots. Nunca falta cuando la lista esta vacia: un dia en blanco sin "
							+ "explicacion es indistinguible de un error del sistema.",
					allowableValues = {
							"FERIADO", "CIERRE", "VINCULO", "SIN_HORARIO", "OFERTA_NO_VIGENTE",
							"SIN_PROFESIONAL", "SIN_ESPACIO", "FRANJA_MAS_CORTA_QUE_LA_OFERTA",
							"COMPLETO"},
					example = "FERIADO")
			String motivoSinSlots,

			@Schema(description = "Slots del dia, ordenados por instante de inicio")
			List<SlotResponse> slots) {
	}

	@Schema(name = "SlotDisponible", description = "Un hueco concreto y reservable")
	public record SlotResponse(

			@Schema(description = "Instante de inicio, UTC", example = "2026-09-15T12:00:00Z")
			Instant desde,

			@Schema(
					description = "Instante de fin, UTC y EXCLUSIVO",
					example = "2026-09-15T12:45:00Z")
			Instant hasta,

			@Schema(
					description = "Membership del profesional que atiende. Ausente si la oferta no "
							+ "requiere profesional.",
					example = "31")
			Long profesionalId,

			@Schema(
					description = "Espacio asignado. **Siempre ausente por ahora**: el motor "
							+ "verifica que exista al menos un espacio habilitado y en servicio, "
							+ "pero no elige cual. Elegirlo es parte de la reserva y tiene que "
							+ "ocurrir bajo el mismo lock que la crea, o dos busquedas "
							+ "concurrentes prometen el mismo box.")
			Long espacioId,

			@Schema(description = "Cuantas reservas admite el turno. 1 individual, mas grupal", example = "1")
			int cupoTotal,

			@Schema(
					description = "Cuantas quedan libres. Un slot completo viaja igual, con cero: "
							+ "la pantalla necesita poder mostrar 'completo' en vez de un hueco en "
							+ "la grilla, que el usuario leeria como 'no atiende a esa hora'.",
					example = "1")
			int cupoLibre) {
	}
}
