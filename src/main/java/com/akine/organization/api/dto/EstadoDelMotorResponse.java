package com.akine.organization.api.dto;

import com.akine.platform.spi.modelo.MotorDeModelos;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Servidor de modelos de lenguaje (Ollama) del despliegue")
public record EstadoDelMotorResponse(

		@Schema(description = "Ollama respondio", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean alcanzable,

		@Schema(description = "Direccion del servidor; ausente si ninguna funcion usa Ollama")
		String url,

		@Schema(description = "Motivo legible si no respondio")
		String error,

		@Schema(description = "Un modelo por nombre, con las funciones que lo usan", requiredMode = Schema.RequiredMode.REQUIRED)
		List<ModeloDelMotorResponse> modelos) {

	@Schema(description = "Un modelo configurado")
	public record ModeloDelMotorResponse(

			@Schema(description = "Nombre en Ollama", example = "qwen3:4b-instruct", requiredMode = Schema.RequiredMode.REQUIRED)
			String nombre,

			@Schema(description = "Funciones que lo usan", example = "[\"ASISTENTE\", \"BIBLIOTECA\"]", requiredMode = Schema.RequiredMode.REQUIRED)
			List<String> funciones,

			@Schema(description = "Ya esta bajado en el servidor", requiredMode = Schema.RequiredMode.REQUIRED)
			boolean descargado,

			@Schema(description = "Esta en memoria, listo para responder", requiredMode = Schema.RequiredMode.REQUIRED)
			boolean cargado,

			@Schema(description = "Memoria que ocupa cargado, en MB", example = "4200", requiredMode = Schema.RequiredMode.REQUIRED)
			long memoriaMb,

			@Schema(description = "De esa memoria, cuanta esta en la placa de video", example = "4200", requiredMode = Schema.RequiredMode.REQUIRED)
			long memoriaGpuMb,

			@Schema(description = "GPU, CPU o MIXTO; ausente si no esta cargado", example = "GPU")
			String ejecucion) {
	}

	public static EstadoDelMotorResponse from(MotorDeModelos.EstadoDelMotor estado) {
		return new EstadoDelMotorResponse(estado.alcanzable(), null, estado.error(), estado.modelos().stream()
				.map(m -> new ModeloDelMotorResponse(m.nombre(), m.funciones(), m.descargado(), m.cargado(),
						m.memoriaMb(), m.memoriaGpuMb(), m.ejecucion()))
				.toList());
	}
}
