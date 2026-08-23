package com.akine.organization.api.dto;

import com.akine.organization.application.SubscriptionTransitionView;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Pagina del historico de la suscripcion.
 *
 * <p><b>Por que un DTO propio y no el {@code Page} de Spring Data.</b> La serializacion por
 * defecto de {@code Page} es una estructura interna de la libreria —{@code pageable},
 * {@code sort}, {@code first}, {@code empty}, campos anidados que cambian entre versiones— y
 * el contrato quedaria atado a ella: actualizar Spring Data rompeia el cliente generado del
 * frontend sin que nadie hubiera tocado la API. Este record publica los cuatro datos que el
 * cliente realmente usa y los deja estables.
 */
@Schema(description = "Pagina del historico de la suscripcion, del hecho mas reciente al mas viejo")
public record SubscriptionTransitionPageResponse(

		@Schema(description = "Hechos de esta pagina")
		List<SubscriptionTransitionResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina", example = "20")
		int size,

		@Schema(description = "Cantidad total de hechos en el historico", example = "37")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "2")
		int totalPages) {

	public static SubscriptionTransitionPageResponse from(Page<SubscriptionTransitionView> page) {
		return new SubscriptionTransitionPageResponse(
				page.getContent().stream().map(SubscriptionTransitionResponse::from).toList(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages());
	}
}
