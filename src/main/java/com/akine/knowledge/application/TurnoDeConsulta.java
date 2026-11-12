package com.akine.knowledge.application;

/** Un turno previo de la conversacion; viaja al modelo solo como historial, nunca como system. */
public record TurnoDeConsulta(Rol rol, String texto) {

	public enum Rol { USUARIO, ASISTENTE }
}
