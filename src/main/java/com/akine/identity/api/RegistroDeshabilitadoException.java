package com.akine.identity.api;

/**
 * El autoregistro esta apagado (akine.registro.autoservicio.enabled, 18/11/2026): los centros
 * los da de alta la plataforma. Se responde 404, como una ruta que no existe.
 */
class RegistroDeshabilitadoException extends RuntimeException {

	RegistroDeshabilitadoException() {
		super("Autoregistro deshabilitado");
	}
}
