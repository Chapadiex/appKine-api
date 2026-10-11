package com.akine.notification.api.dto;

import com.akine.notification.application.ConsultaDeNotificacionesService.NotificacionResumen;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Pagina de notificaciones del tenant. Misma forma que el resto de los listados paginados de la
 * API: el {@code Page} de Spring Data no se publica tal cual.
 */
@Schema(description = "Pagina de notificaciones de la organizacion")
public record NotificationPageResponse(

		@Schema(description = "Notificaciones de esta pagina, de la mas nueva a la mas vieja")
		List<NotificationResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina", example = "20")
		int size,

		@Schema(description = "Cantidad total de notificaciones que cumplen el filtro", example = "3")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "1")
		int totalPages) {

	public static NotificationPageResponse de(Page<NotificacionResumen> page) {
		return new NotificationPageResponse(
				page.getContent().stream().map(NotificationResponse::de).toList(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages());
	}
}
