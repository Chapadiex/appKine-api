package com.akine.clinical.application;

/**
 * El contenido de un adjunto clinico, ya autorizado y ya auditado, listo para que {@code api} lo
 * entregue.
 *
 * <p>{@code nombreArchivo} y {@code contentType} viajan junto a los bytes porque el que sabe que
 * archivo es esto es {@code application}, y hacer que el controller vuelva a consultarlo lo
 * obligaria a una segunda lectura autorizada de lo mismo — que en un modulo clinico significaria
 * ademas un segundo evento de auditoria por una sola descarga.
 */
public record ContenidoDeAdjuntoClinico(
		String nombreArchivo, String contentType, byte[] contenido) {
}
