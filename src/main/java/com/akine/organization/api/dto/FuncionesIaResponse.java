package com.akine.organization.api.dto;

import com.akine.organization.application.FuncionesIaView;
import com.akine.organization.spi.FuncionIa;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(description = "Funciones de IA del centro: lo que ofrece el servidor, lo que eligio el centro y lo que "
		+ "queda activo")
public record FuncionesIaResponse(

		@Schema(description = "Identificador de la organizacion", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		long organizationId,

		@Schema(description = "Version para el bloqueo optimista; 0 si el centro nunca la edito", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
		long version,

		@Schema(description = "Una entrada por funcion, siempre las cuatro", requiredMode = Schema.RequiredMode.REQUIRED)
		List<FuncionIaResponse> funciones) {

	@Schema(description = "Estado de una funcion de IA")
	public record FuncionIaResponse(

			@Schema(description = "Funcion", example = "ASISTENTE", requiredMode = Schema.RequiredMode.REQUIRED)
			FuncionIa funcion,

			@Schema(description = "El servidor la ofrece (variables de entorno del despliegue)", requiredMode = Schema.RequiredMode.REQUIRED)
			boolean despliegue,

			@Schema(description = "El centro la quiere usar. Para SUGERENCIAS es solo su propio interruptor", requiredMode = Schema.RequiredMode.REQUIRED)
			boolean centro,

			@Schema(description = "Queda disponible: despliegue y centro prendidos. SUGERENCIAS exige ademas "
					+ "EVOLUCION y BIBLIOTECA", requiredMode = Schema.RequiredMode.REQUIRED)
			boolean activa) {
	}

	public static FuncionesIaResponse from(FuncionesIaView vista) {
		return new FuncionesIaResponse(vista.organizationId(), vista.version(), vista.funciones().stream()
				.map(f -> new FuncionIaResponse(f.funcion(), f.despliegue(), f.centro(), f.activa()))
				.toList());
	}
}
