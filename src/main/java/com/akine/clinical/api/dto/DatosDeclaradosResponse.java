package com.akine.clinical.api.dto;

import com.akine.clinical.application.DatosDeclaradosService.DatosDeclaradosView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Lo que el paciente declaro en su historia (DP-47). Solo trae datos DECLARADO: "
		+ "lo que cargo un profesional no aparece")
public record DatosDeclaradosResponse(

		@Schema(description = "Historia clinica de la persona, o null si todavia no tiene", example = "88")
		Long historiaClinicaId,

		@Schema(description = "Antecedentes declarados vigentes, mas recientes primero")
		List<AntecedenteResponse> antecedentes,

		@Schema(description = "Archivos declarados vigentes, mas recientes primero")
		List<AdjuntoClinicoResponse> adjuntos,

		@Schema(description = "Ficha de ingreso de la historia, o null si todavia no se cargo")
		FichaDeIngresoResponse ficha) {

	public static DatosDeclaradosResponse from(DatosDeclaradosView view) {
		return new DatosDeclaradosResponse(
				view.historiaClinicaId(),
				view.antecedentes().stream().map(AntecedenteResponse::from).toList(),
				view.adjuntos().stream().map(AdjuntoClinicoResponse::from).toList(),
				view.ficha() == null ? null : FichaDeIngresoResponse.from(view.ficha()));
	}
}
