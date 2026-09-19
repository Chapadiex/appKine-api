package com.akine.clinical.application;

import com.akine.clinical.spi.EventoClinico;

import java.util.List;

/**
 * Una pagina del timeline clinico y por donde seguir leyendo.
 *
 * <p>No trae total ni cantidad de paginas, y no es una omision: contarlas obligaria a preguntarle
 * a las cuatro fuentes cuantos eventos tienen en total, que es el doble de consultas para un
 * numero que en una linea de tiempo infinita no se usa. Lo unico que el cliente necesita saber es
 * si hay mas, y eso lo dice {@code proximoCursor}.
 *
 * @param eventos       los eventos de esta pagina, mas nuevos primero, ya mezclados y recortados
 * @param proximoCursor cursor opaco para pedir la pagina siguiente, o {@code null} si no hay mas.
 *                      <b>{@code null} significa fin</b>, no error: un cliente que reintenta con
 *                      el mismo cursor recibe la misma pagina, que es lo correcto
 */
public record TimelinePagina(List<EventoClinico> eventos, String proximoCursor) {

	public TimelinePagina {
		eventos = List.copyOf(eventos);
	}
}
