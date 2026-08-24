package com.akine.organization.api.dto;

import com.akine.organization.application.MembershipView;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Pagina de colaboradores del tenant.
 *
 * <p>Misma forma que el resto de los listados paginados de la API y por el mismo motivo que
 * {@link SubscriptionTransitionPageResponse}: el {@code Page} de Spring Data serializa su
 * estructura interna, y publicarla ataria el cliente generado a la version de la libreria.
 */
@Schema(description = "Pagina de colaboradores de la organizacion")
public record MembershipPageResponse(

		@Schema(description = "Vinculos de esta pagina")
		List<MembershipResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina", example = "20")
		int size,

		@Schema(description = "Cantidad total de vinculos, incluidos los revocados", example = "37")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "2")
		int totalPages) {

	public static MembershipPageResponse from(Page<MembershipView> page) {
		return new MembershipPageResponse(
				page.getContent().stream().map(MembershipResponse::from).toList(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages());
	}
}
