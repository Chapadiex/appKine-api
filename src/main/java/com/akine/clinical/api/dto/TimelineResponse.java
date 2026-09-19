package com.akine.clinical.api.dto;

import com.akine.clinical.application.TimelinePagina;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Una pagina del timeline clinico y por donde seguir.
 *
 * <p><b>No lleva total ni cantidad de paginas</b>, y no es una omision. Contarlos obligaria a
 * preguntarle a cada fuente cuantos eventos tiene en total: el doble de consultas para un numero
 * que en una linea de tiempo infinita no se usa. Lo unico que el cliente necesita saber es si hay
 * mas, y eso lo dice {@code proximoCursor}.
 */
@Schema(description = "Pagina del timeline clinico, mas nuevos primero")
public record TimelineResponse(

		@Schema(description = "Eventos de esta pagina, ya mezclados de todas las fuentes y "
				+ "ordenados de mas nuevo a mas viejo")
		List<EventoClinicoResponse> eventos,

		@Schema(description = "Cursor OPACO para pedir la pagina siguiente. null significa que no "
				+ "hay mas, no que hubo un error. Su formato puede cambiar sin aviso: la unica "
				+ "forma valida de obtener uno es haber leido la pagina anterior")
		String proximoCursor) {

	public static TimelineResponse from(TimelinePagina pagina) {
		return new TimelineResponse(
				pagina.eventos().stream().map(EventoClinicoResponse::from).toList(),
				pagina.proximoCursor());
	}
}
