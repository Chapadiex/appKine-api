package com.akine.platform.spi.modelo;

/** Un mensaje de la conversacion con el modelo de lenguaje. */
public record MensajeChat(Rol rol, String texto) {

	public enum Rol { SISTEMA, USUARIO, ASISTENTE }
}
