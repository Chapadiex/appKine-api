package com.akine.scheduling.api.dto;

import com.akine.scheduling.application.CambioDeAgendaView;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(name = "HistorialDeAgendaPage", description = "Pagina del historial de agenda de un profesional")
public record HistorialDeAgendaPageResponse(

		@Schema(description = "Cambios de esta pagina")
		List<CambioDeAgendaResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Elementos por pagina. Nunca mayor a 100", example = "10")
		int size,

		@Schema(description = "Cambios que cumplen el filtro", example = "37")
		long totalElements,

		@Schema(description = "Paginas disponibles", example = "4")
		int totalPages) {

	public static HistorialDeAgendaPageResponse de(Page<CambioDeAgendaView> pagina) {
		return new HistorialDeAgendaPageResponse(
				pagina.getContent().stream().map(CambioDeAgendaResponse::de).toList(),
				pagina.getNumber(), pagina.getSize(), pagina.getTotalElements(), pagina.getTotalPages());
	}
}
