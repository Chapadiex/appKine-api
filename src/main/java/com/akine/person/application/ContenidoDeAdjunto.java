package com.akine.person.application;

/**
 * El contenido de un adjunto, ya autorizado, listo para que {@code api} lo entregue.
 *
 * <p>{@code nombreArchivo} y {@code contentType} viajan junto a los bytes porque el que sabe que
 * archivo es esto es {@code application}, y hacer que el controller vuelva a consultarlo lo
 * obligaria a una segunda lectura autorizada de lo mismo.
 */
public record ContenidoDeAdjunto(String nombreArchivo, String contentType, byte[] contenido) {
}
