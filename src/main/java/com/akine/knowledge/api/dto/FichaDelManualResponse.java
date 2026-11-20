package com.akine.knowledge.api.dto;

import com.akine.knowledge.application.manual.ManualService;
import com.akine.knowledge.domain.manual.FichaDelManual;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** Una ficha del manual, con su cuerpo en Markdown. */
@Schema(description = "Ficha del manual de una pantalla")
public record FichaDelManualResponse(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String titulo,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String grupo,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Cuerpo en Markdown: titulos, listas, "
				+ "negrita, avisos `> [!importante]`, iconos `:icono[x]:` y capturas `![alt](captura:ficha/nombre)`")
		String markdown,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<FichaRelacionadaResponse> relacionadas) {

	/** Una ficha relacionada, con lo justo para enlazarla. */
	@Schema(description = "Ficha relacionada")
	public record FichaRelacionadaResponse(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String titulo) {
	}

	/** Las relacionadas ya vienen filtradas: las que la persona no ve no viajan. */
	public static FichaDelManualResponse de(ManualService.FichaVisible visible) {
		FichaDelManual ficha = visible.ficha();
		List<FichaRelacionadaResponse> relacionadas = visible.relacionadas().stream()
				.map(r -> new FichaRelacionadaResponse(r.id(), r.titulo()))
				.toList();
		return new FichaDelManualResponse(ficha.id(), ficha.titulo(), ficha.grupo(), ficha.markdown(), relacionadas);
	}
}
