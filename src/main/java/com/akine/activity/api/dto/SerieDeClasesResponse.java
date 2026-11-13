package com.akine.activity.api.dto;

import com.akine.activity.application.ResultadoDeSerieDeClases;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

@Schema(
		name = "SerieDeClases",
		description = "Resultado de programar una serie: las clases programadas y las fechas que no se "
				+ "pudieron programar, cada una con su motivo")
public record SerieDeClasesResponse(

		@Schema(description = "Clases de la serie, en orden de fecha. En un reintento con la misma "
				+ "clave incluye las que ya existian")
		List<ClaseResponse> programadas,

		@Schema(description = "Fechas que no se programaron, en orden. Vacio si salieron todas")
		List<FechaOmitida> omitidas) {

	public static SerieDeClasesResponse de(ResultadoDeSerieDeClases resultado) {
		return new SerieDeClasesResponse(
				resultado.programadas().stream().map(ClaseResponse::de).toList(),
				resultado.omitidas().stream()
						.map(omitida -> new FechaOmitida(omitida.fecha(), omitida.motivo().name(), omitida.detalle()))
						.toList());
	}

	@Schema(name = "FechaOmitidaDeSerie", description = "Una fecha de la serie que no se programo")
	public record FechaOmitida(

			@Schema(description = "Fecha local, en la zona de la sede", example = "2026-11-24")
			LocalDate fecha,

			@Schema(
					description = "Por que no se programo. PASADO: la fecha ya paso. FERIADO: la sede no "
							+ "atiende. OFERTA_NO_VIGENTE: la oferta no rige ese dia. HORARIO_NO_DISPONIBLE: "
							+ "el profesional no atiende, no esta habilitado o hay un cierre. RECURSO_OCUPADO: "
							+ "el profesional o el box tienen un turno o una clase que se cruza. CAPACIDAD: el "
							+ "cupo supera el de la oferta o el del box. CLAVE_REUSADA: la clave ya programo "
							+ "otra clase esa fecha",
					allowableValues = {"PASADO", "FERIADO", "OFERTA_NO_VIGENTE", "HORARIO_NO_DISPONIBLE",
							"RECURSO_OCUPADO", "CAPACIDAD", "CLAVE_REUSADA"},
					example = "RECURSO_OCUPADO")
			String motivo,

			@Schema(description = "El mensaje que daria esa fecha programada como clase suelta",
					example = "El espacio ya esta ocupado en ese horario")
			String detalle) {
	}
}
