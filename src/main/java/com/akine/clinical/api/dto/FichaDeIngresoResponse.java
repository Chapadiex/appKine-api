package com.akine.clinical.api.dto;

import com.akine.clinical.application.FichaDeIngresoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

@Schema(description = "Ficha de ingreso del paciente, con el IMC calculado y su estado de revision")
public record FichaDeIngresoResponse(

		@Schema(description = "Version para el proximo guardado", example = "3",
				requiredMode = Schema.RequiredMode.REQUIRED)
		long version,

		@Schema(description = "Ocupacion elegida, o null", example = "17")
		Long ocupacionId,

		@Schema(description = "Nombre de la ocupacion, o null", example = "Docente")
		String ocupacionNombre,

		@Schema(description = "Peso en kg, o null", example = "72.0")
		BigDecimal pesoKg,

		@Schema(description = "Altura en cm, o null", example = "168.0")
		BigDecimal alturaCm,

		@Schema(description = "IMC calculado con un decimal, o null si falta peso o altura", example = "25.5")
		BigDecimal imc,

		@Schema(description = "Nivel de actividad fisica, o null", example = "REGULAR",
				allowableValues = {"SEDENTARIO", "OCASIONAL", "REGULAR", "DEPORTISTA"})
		String actividad,

		@Schema(description = "Que actividad hace, o null")
		String detalle,

		@Schema(description = "Quien la guardo por ultima vez: PROFESIONAL (queda revisada) o DECLARADO "
				+ "(espera revision)", example = "DECLARADO", allowableValues = {"PROFESIONAL", "DECLARADO"},
				requiredMode = Schema.RequiredMode.REQUIRED)
		String origen,

		@Schema(description = "Nombre de quien la guardo por ultima vez, o null")
		String cargadoPorNombre,

		@Schema(description = "Nombre de quien la reviso, o null")
		String revisadoPorNombre,

		@Schema(description = "Instante de la revision, o null si espera revision")
		Instant revisadoEn,

		@Schema(description = "Ultimo guardado")
		Instant actualizadaEn) {

	public static FichaDeIngresoResponse from(FichaDeIngresoView view) {
		return new FichaDeIngresoResponse(view.version(), view.ocupacionId(), view.ocupacionNombre(),
				view.pesoKg(), view.alturaCm(), view.imc(), view.actividad(), view.detalle(), view.origen(),
				view.cargadoPorNombre(), view.revisadoPorNombre(), view.revisadoEn(), view.actualizadaEn());
	}
}
