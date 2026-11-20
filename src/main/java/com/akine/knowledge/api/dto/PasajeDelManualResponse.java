package com.akine.knowledge.api.dto;

import com.akine.knowledge.application.manual.IndiceDelManual;
import com.akine.knowledge.application.manual.PasajeEncontrado;
import io.swagger.v3.oas.annotations.media.Schema;

/** Un resultado de la busqueda en el manual. */
@Schema(description = "Pasaje del manual encontrado por la busqueda")
public record PasajeDelManualResponse(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fichaId,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fichaTitulo,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String pasajeId,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String titulo,
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED, maxLength = FRAGMENTO_MAXIMO,
				description = "El comienzo del pasaje, sin Markdown") String fragmento,
		@Schema(description = "Ruta de la pantalla, o null si todas sus rutas llevan parametros") String enlace) {

	static final int FRAGMENTO_MAXIMO = 240;

	public static PasajeDelManualResponse de(PasajeEncontrado p) {
		String texto = IndiceDelManual.textoPlano(p.pasaje().markdown());
		if (texto.length() > FRAGMENTO_MAXIMO) {
			texto = texto.substring(0, FRAGMENTO_MAXIMO - 1) + "…";
		}
		String enlace = p.ficha().rutas().stream().filter(r -> !r.contains(":")).findFirst().orElse(null);
		return new PasajeDelManualResponse(p.ficha().id(), p.ficha().titulo(), p.pasaje().id(), p.pasaje().titulo(),
				texto, enlace);
	}
}
