package com.akine.clinical.api.dto;

import com.akine.clinical.application.DatosAdministrativosDeHistoriaView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Un caso clinico sin su contenido clinico: lo que ve quien solo tiene {@code paciente:read}.
 * No lleva diagnostico presuntivo, objetivo terapeutico ni motivo de cierre.
 */
@Schema(description = "Caso clinico sin contenido clinico: numero, estado, fechas y equipo vigente")
public record CasoAdministrativoResponse(

		@Schema(example = "301")
		long id,

		@Schema(description = "Correlativo del caso dentro de la historia", example = "2")
		int numeroCaso,

		@Schema(allowableValues = {"ACTIVO", "CERRADO"}, example = "ACTIVO")
		String estado,

		@Schema(description = "Oferta por la que se abrio el caso", example = "14")
		long ofertaId,

		@Schema(description = "Sede de esa oferta", example = "3")
		long ofertaConsultorioId,

		@Schema(description = "Instante UTC de apertura")
		Instant abiertoEn,

		@Schema(description = "Instante UTC de cierre. Ausente mientras el caso siga activo")
		Instant cerradoEn,

		@ArraySchema(arraySchema = @Schema(description = "Profesionales tratantes vigentes"))
		List<CasoProfesionalResponse> equipo) {

	public static CasoAdministrativoResponse from(DatosAdministrativosDeHistoriaView.Caso caso) {
		return new CasoAdministrativoResponse(
				caso.id(),
				caso.numeroCaso(),
				caso.estado(),
				caso.ofertaId(),
				caso.ofertaConsultorioId(),
				caso.abiertoEn(),
				caso.cerradoEn(),
				caso.equipo().stream().map(CasoProfesionalResponse::from).toList());
	}
}
