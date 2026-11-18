package com.akine.organization.api.dto;

import com.akine.platform.spi.modelo.MotorDeModelos;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Resultado de una pregunta minima al modelo")
public record PruebaDelMotorResponse(

		@Schema(description = "El modelo contesto", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean ok,

		@Schema(description = "Modelo probado", example = "qwen3:4b-instruct")
		String modelo,

		@Schema(description = "Cuanto tardo, incluida la carga si no estaba en memoria", example = "850", requiredMode = Schema.RequiredMode.REQUIRED)
		long milisegundos,

		@Schema(description = "Motivo legible si fallo")
		String error) {

	public static PruebaDelMotorResponse from(MotorDeModelos.PruebaDelMotor prueba) {
		return new PruebaDelMotorResponse(prueba.ok(), prueba.modelo(), prueba.milisegundos(), prueba.error());
	}
}
