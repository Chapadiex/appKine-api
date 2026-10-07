package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.AlcanceDeSerieView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(
		name = "AlcanceDeSerie",
		description = "Que turnos toca una operacion con alcance y cuales deja como estan. En la "
				+ "previsualizacion, lo que tocaria; en la respuesta del comando, lo que toco.")
public record AlcanceDeSerieResponse(

		@Schema(example = "12")
		long serieId,

		@Schema(allowableValues = {"ESTE", "ESTE_Y_SIGUIENTES", "TODA_LA_SERIE"}, example = "ESTE_Y_SIGUIENTES")
		String alcance,

		@Schema(description = "Turno pivote, si se indico", example = "301")
		Long turnoId,

		@Schema(description = "Turnos futuros pendientes que la operacion toca. Su tamano es lo que "
				+ "se confirma en `cantidadConfirmada`.")
		List<TurnoResponse> afectados,

		@Schema(description = "Candidatos que NO se tocan, con su motivo")
		List<TurnoOmitidoResponse> omitidos) {

	public static AlcanceDeSerieResponse de(AlcanceDeSerieView vista) {
		return new AlcanceDeSerieResponse(
				vista.serieId(), vista.alcance(), vista.turnoId(),
				vista.afectados().stream().map(TurnoResponse::de).toList(),
				vista.omitidos().stream()
						.map(omitido -> new TurnoOmitidoResponse(
								TurnoResponse.de(omitido.turno()), omitido.motivo()))
						.toList());
	}
}
