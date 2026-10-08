package com.akine.person.api.dto;

import com.akine.person.application.HistorialDeAutorizacionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * Una pagina del historial de una autorizacion, con el vencimiento calculado (DP-23, AKINE B-4).
 * Misma forma de paginado que {@link AdjuntoPageResponse}.
 */
@Schema(description = "Pagina del historial de estados de una autorizacion")
public record HistorialDeAutorizacionResponse(

		@Schema(description = "Autorizacion", example = "77")
		long autorizacionId,

		@Schema(description = "Estado actual de la autorizacion",
				example = "APROBADA",
				allowableValues = {"PENDIENTE", "APROBADA", "OBSERVADA", "RECHAZADA"})
		String estadoActual,

		@Schema(description = "Ciclo de vida actual: false si se dio de baja")
		boolean activa,

		@Schema(description = "Vencida a la fecha consultada. CALCULADO: el vencimiento no es un "
				+ "evento del historial, es funcion del reloj y nada lo escribe")
		boolean vencida,

		@Schema(description = "Primer dia en que la autorizacion dejo de valer (el siguiente al "
				+ "ultimo dia de vigencia). Null si no esta vencida", example = "2027-01-01")
		LocalDate vencidaDesde,

		@Schema(description = "Eventos de esta pagina, del mas viejo al mas nuevo")
		List<EventoDeAutorizacionResponse> content,

		@Schema(description = "Numero de pagina devuelta, base cero", example = "0")
		int page,

		@Schema(description = "Cantidad de elementos por pagina", example = "50")
		int size,

		@Schema(description = "Cantidad total de eventos", example = "6")
		long totalElements,

		@Schema(description = "Cantidad total de paginas disponibles", example = "1")
		int totalPages) {

	public static HistorialDeAutorizacionResponse of(
			HistorialDeAutorizacionView historial, int page, int size) {

		return new HistorialDeAutorizacionResponse(
				historial.autorizacionId(),
				historial.estadoActual(),
				historial.activa(),
				historial.vencida(),
				historial.vencidaDesde(),
				historial.contenido().stream().map(EventoDeAutorizacionResponse::de).toList(),
				page,
				size,
				historial.total(),
				(int) Math.ceil((double) historial.total() / size));
	}
}
