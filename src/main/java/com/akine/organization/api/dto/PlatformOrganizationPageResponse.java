package com.akine.organization.api.dto;

import com.akine.organization.domain.OrganizationListing;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Pagina del listado de centros de la administracion de plataforma. DTO propio y no el
 * {@code Page} de Spring Data, por el mismo motivo que {@link SubscriptionTransitionPageResponse}.
 */
@Schema(description = "Pagina del listado de centros de la administracion de plataforma, "
		+ "ordenada por nombre")
public record PlatformOrganizationPageResponse(

		@Schema(description = "Centros de esta pagina")
		List<PlatformOrganizationResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina", example = "20")
		int size,

		@Schema(description = "Cantidad total de centros que cumplen el filtro", example = "37")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "2")
		int totalPages) {

	public static PlatformOrganizationPageResponse from(Page<OrganizationListing> page) {
		return new PlatformOrganizationPageResponse(
				page.getContent().stream().map(PlatformOrganizationResponse::from).toList(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages());
	}
}
