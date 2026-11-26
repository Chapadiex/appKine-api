package com.akine.organization.api.dto;

import com.akine.platform.spi.modelo.MotorDeModelos;
import com.akine.platform.spi.modelo.agente.ConfiguracionDelAgente;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Servidor de modelos de lenguaje (Ollama) del despliegue y proveedor del asistente")
public record EstadoDelMotorResponse(

		@Schema(description = "Ollama respondio", requiredMode = Schema.RequiredMode.REQUIRED)
		boolean alcanzable,

		@Schema(description = "Direccion del servidor; ausente si ninguna funcion usa Ollama")
		String url,

		@Schema(description = "Motivo legible si no respondio")
		String error,

		@Schema(description = "Un modelo por nombre, con las funciones que lo usan", requiredMode = Schema.RequiredMode.REQUIRED)
		List<ModeloDelMotorResponse> modelos,

		@Schema(description = "Proveedor del agente del asistente configurado en el despliegue; ausente si no se "
				+ "pudo leer. Nunca trae la key, solo si hay una")
		ProveedorDelAsistenteResponse proveedorAsistente) {

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

	@Schema(description = "Proveedor del agente del asistente: el principal y, si hay, el alternativo")
	public record ProveedorDelAsistenteResponse(

			@Schema(description = "claude, compatible o prueba", example = "claude", requiredMode = Schema.RequiredMode.REQUIRED)
			String nombre,

			@Schema(description = "Modelo configurado; ausente si el proveedor no tiene uno", example = "claude-opus-5-5")
			String modelo,

			@Schema(description = "Si hay una key cargada (la key nunca viaja)", requiredMode = Schema.RequiredMode.REQUIRED)
			boolean tieneKey,

			@Schema(description = "Proveedor al que se cae si el principal no responde; ausente si no hay")
			ProveedorAlternativoResponse alternativo) {
	}

	@Schema(description = "Proveedor alternativo del agente del asistente")
	public record ProveedorAlternativoResponse(

			@Schema(description = "claude, compatible o prueba", example = "compatible", requiredMode = Schema.RequiredMode.REQUIRED)
			String nombre,

			@Schema(description = "Modelo configurado; ausente si el proveedor no tiene uno", example = "qwen3:4b-instruct")
			String modelo,

			@Schema(description = "Si hay una key cargada (la key nunca viaja)", requiredMode = Schema.RequiredMode.REQUIRED)
			boolean tieneKey) {
	}

	public static EstadoDelMotorResponse from(MotorDeModelos.EstadoDelMotor estado, ConfiguracionDelAgente agente) {
		return new EstadoDelMotorResponse(estado.alcanzable(), null, estado.error(), estado.modelos().stream()
				.map(m -> new ModeloDelMotorResponse(m.nombre(), m.funciones(), m.descargado(), m.cargado(),
						m.memoriaMb(), m.memoriaGpuMb(), m.ejecucion()))
				.toList(), proveedorDe(agente));
	}

	private static ProveedorDelAsistenteResponse proveedorDe(ConfiguracionDelAgente agente) {
		ConfiguracionDelAgente.ProveedorConfigurado principal = agente == null ? null : agente.principal();
		if (principal == null) {
			return null;
		}
		ConfiguracionDelAgente.ProveedorConfigurado alt = agente.alternativo();
		return new ProveedorDelAsistenteResponse(principal.nombre(), principal.modelo(), principal.tieneKey(),
				alt == null ? null : new ProveedorAlternativoResponse(alt.nombre(), alt.modelo(), alt.tieneKey()));
	}
}
