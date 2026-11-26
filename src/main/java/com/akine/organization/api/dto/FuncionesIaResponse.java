package com.akine.organization.api.dto;

import com.akine.organization.application.FuncionesIaView;
import com.akine.organization.spi.FuncionIa;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "Funciones de IA del centro: lo que ofrece el servidor, lo que eligio el centro y lo que "
		+ "queda activo")
public record FuncionesIaResponse(

		@Schema(description = "Identificador de la organizacion", example = "1", requiredMode = Schema.RequiredMode.REQUIRED)
		long organizationId,

		@Schema(description = "Version para el bloqueo optimista; 0 si el centro nunca la edito", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
		long version,

		@Schema(description = "Una entrada por funcion, siempre las cuatro", requiredMode = Schema.RequiredMode.REQUIRED)
		List<FuncionIaResponse> funciones,

		@Schema(description = "Consentimiento del centro para usar IA (Ley 25.326). Sin aceptar la version vigente "
				+ "ninguna funcion queda activa", requiredMode = Schema.RequiredMode.REQUIRED)
		ConsentimientoIaResponse consentimiento) {

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

	@Schema(description = "Estado del consentimiento de IA del centro")
	public record ConsentimientoIaResponse(

			@Schema(description = "El despliegue lo exige. Si es false, lo demas viene vacio y no bloquea nada", requiredMode = Schema.RequiredMode.REQUIRED)
			boolean exigido,

			@Schema(description = "Version vigente del texto, la que hay que aceptar", example = "2026-11-26")
			String versionVigente,

			@Schema(description = "El centro acepto la version vigente", requiredMode = Schema.RequiredMode.REQUIRED)
			boolean aceptado,

			@Schema(description = "Nombre de quien acepto, copiado al aceptar", example = "Ana Gomez")
			String aceptadoPor,

			@Schema(description = "Cuando se acepto")
			Instant aceptadoAt) {
	}

	public static FuncionesIaResponse from(FuncionesIaView vista) {
		return new FuncionesIaResponse(vista.organizationId(), vista.version(), vista.funciones().stream()
				.map(f -> new FuncionIaResponse(f.funcion(), f.despliegue(), f.centro(), f.activa()))
				.toList(),
				new ConsentimientoIaResponse(vista.consentimiento().exigido(), vista.consentimiento().versionVigente(),
						vista.consentimiento().aceptado(), vista.consentimiento().aceptadoPor(),
						vista.consentimiento().aceptadoAt()));
	}
}
