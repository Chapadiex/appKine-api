package com.akine.encounter.api.dto;

import com.akine.encounter.application.SugerenciasView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/** El resultado de pedir sugerencias de evolucion para un caso (M31 fase 2). */
@Schema(description = "Resultado de un pedido de sugerencias de evolucion")
public record SugerenciaDeEvolucionGeneracionResponse(

		@Schema(description = "Agrupa las sugerencias de este pedido")
		UUID generacionId,

		@Schema(description = "SUGERENCIAS: el modelo se llamo (puede traer cero si todas se descartaron). "
				+ "SIN_MATERIAL: la biblioteca no tiene material suficiente para ningun hallazgo. "
				+ "SIN_HALLAZGOS_ELEGIBLES: el caso no tiene hallazgos que respaldar",
				allowableValues = {"SUGERENCIAS", "SIN_MATERIAL", "SIN_HALLAZGOS_ELEGIBLES"})
		String resultado,

		@Schema(description = "Las sugerencias que pasaron el validador, ya guardadas como PENDIENTE")
		List<SugerenciaDeEvolucionResponse> sugerencias,

		@Schema(description = "Cuantas propuso el modelo y no pasaron el validador; no se guardan", example = "1")
		int descartadas,

		@Schema(description = "TEXTO_OMITIDO si algun texto parecia una instruccion y no viajo al modelo, "
				+ "y lo que el analisis dejo afuera (sesiones sin caso, recorte por tope)")
		List<String> advertencias) {

	public static SugerenciaDeEvolucionGeneracionResponse de(SugerenciasView v) {
		return new SugerenciaDeEvolucionGeneracionResponse(v.generacionId(), v.resultado().name(),
				v.sugerencias().stream().map(SugerenciaDeEvolucionResponse::de).toList(), v.descartadas(),
				v.advertencias());
	}
}
