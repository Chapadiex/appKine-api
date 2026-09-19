package com.akine.clinical.domain.exception;

/**
 * El cursor de paginacion del timeline no se pudo decodificar.
 *
 * <p><b>Es 400 y no 404 ni 409.</b> No hay conflicto de estado ni recurso ausente: lo que llego
 * mal es un dato del pedido. El cursor es <b>opaco para el cliente</b> —base64 de tres campos— y
 * la unica forma legitima de obtener uno es leer la pagina anterior, asi que uno que no parsea o
 * es un cliente que lo construyo a mano o es uno que lo trunco.
 *
 * <p>No se reinterpreta como "primera pagina", y esa es la decision que importa: contestar la
 * primera pagina ante un cursor roto haria que un cliente con un bug de paginacion recorriera la
 * misma pagina para siempre sin que nadie lo note.
 *
 * <p>El mensaje no incluye el cursor recibido: es entrada del cliente y termina en los logs.
 */
public class CursorInvalidoException extends RuntimeException {

	public CursorInvalidoException(String detalle) {
		super("El cursor de paginacion no es valido: " + detalle);
	}
}
