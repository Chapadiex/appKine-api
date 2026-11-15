package com.akine.encounter.api.dto;

import com.akine.encounter.application.SugerenciasView;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "Pagina del historial de sugerencias de evolucion de un caso, de la mas nueva a la mas vieja")
public record SugerenciaDeEvolucionPageResponse(
		List<SugerenciaDeEvolucionResponse> content,
		@Schema(description = "Numero de pagina, base cero") int page,
		@Schema(description = "Elementos por pagina") int size,
		long totalElements,
		int totalPages) {

	public static SugerenciaDeEvolucionPageResponse de(Page<SugerenciasView.Sugerencia> page) {
		return new SugerenciaDeEvolucionPageResponse(
				page.getContent().stream().map(SugerenciaDeEvolucionResponse::de).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}
}
