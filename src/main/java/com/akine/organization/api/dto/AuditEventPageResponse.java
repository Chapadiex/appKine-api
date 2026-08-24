package com.akine.organization.api.dto;

import com.akine.platform.spi.audit.AuditEventSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Pagina del registro de auditoria.
 *
 * <p>El tamano de pagina que devuelve puede ser <b>menor</b> que el pedido: la consulta de
 * auditoria recorta a 100 elementos por request. Es recorte, no rechazo — un cliente que pide
 * diez mil filas esta mal configurado, no atacando, y devolverle cien le sirve mas que un 400.
 */
@Schema(description = "Pagina del registro de auditoria, del hecho mas reciente al mas viejo")
public record AuditEventPageResponse(

		@Schema(description = "Hechos de esta pagina")
		List<AuditEventResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina. Nunca mayor a 100", example = "20")
		int size,

		@Schema(description = "Cantidad total de hechos que cumplen el filtro", example = "412")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "21")
		int totalPages) {

	public static AuditEventPageResponse from(Page<AuditEventSummary> page) {
		return new AuditEventPageResponse(
				page.getContent().stream().map(AuditEventResponse::from).toList(),
				page.getNumber(),
				page.getSize(),
				page.getTotalElements(),
				page.getTotalPages());
	}
}
