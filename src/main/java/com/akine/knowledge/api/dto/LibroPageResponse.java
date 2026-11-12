package com.akine.knowledge.api.dto;

import com.akine.knowledge.application.LibroView;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

@Schema(description = "Pagina de libros de la biblioteca")
public record LibroPageResponse(
		List<LibroResponse> content,
		@Schema(description = "Numero de pagina, base cero") int page,
		@Schema(description = "Elementos por pagina") int size,
		long totalElements,
		int totalPages) {

	public static LibroPageResponse de(Page<LibroView> page) {
		return new LibroPageResponse(
				page.getContent().stream().map(LibroResponse::de).toList(),
				page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
	}
}
