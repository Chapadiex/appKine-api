package com.akine.knowledge.api.dto;

import com.akine.knowledge.domain.manual.FichaDelManual;
import com.akine.knowledge.domain.manual.PasajeDelManual;
import com.akine.knowledge.domain.manual.TipoDePasaje;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** El indice del manual: las fichas que la persona puede ver, por grupo del menu. */
@Schema(description = "Indice del manual, por grupo")
public record IndiceDelManualResponse(
		@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<GrupoDelManualResponse> grupos) {

	@Schema(description = "Un grupo del menu con sus fichas")
	public record GrupoDelManualResponse(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String nombre,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<EntradaDelManualResponse> fichas) {
	}

	@Schema(description = "Una ficha del indice, con los titulos de sus tareas")
	public record EntradaDelManualResponse(
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String id,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String titulo,
			@Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> tareas) {
	}

	public static IndiceDelManualResponse de(List<FichaDelManual> fichas) {
		Map<String, List<EntradaDelManualResponse>> porGrupo = new LinkedHashMap<>();
		for (FichaDelManual ficha : fichas) {
			List<String> tareas = ficha.pasajes().stream()
					.filter(p -> p.tipo() == TipoDePasaje.TAREA)
					.map(PasajeDelManual::titulo)
					.toList();
			porGrupo.computeIfAbsent(ficha.grupo(), g -> new ArrayList<>())
					.add(new EntradaDelManualResponse(ficha.id(), ficha.titulo(), tareas));
		}
		return new IndiceDelManualResponse(porGrupo.entrySet().stream()
				.map(e -> new GrupoDelManualResponse(e.getKey(), List.copyOf(e.getValue())))
				.toList());
	}
}
