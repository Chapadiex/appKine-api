package com.akine.identity.domain;

/**
 * Por que dejo de valer una sesion de refresh.
 *
 * <p>No es decoracion: es lo que permite distinguir, mirando la tabla seis meses despues, un
 * cierre de sesion normal de la respuesta automatica a un robo de cookie.
 */
public enum MotivoRevocacion {

	/** La persona cerro sesion. Revoca la familia entera. */
	LOGOUT,

	/**
	 * Se presento un refresh ya usado: alguien tiene una copia. Revoca la familia completa,
	 * lo que deja afuera tanto al atacante como a la victima, que vuelve a autenticarse.
	 */
	ROTACION_REUSO,

	/** Suspension administrativa de la cuenta. */
	BLOQUEO,

	/** Baja logica de la cuenta. */
	DESACTIVACION,

	/**
	 * Se fijo una contrasena nueva. Quien resetea puede estar expulsando a un intruso con
	 * sesion viva: dejarle el refresh haria inutil el reset.
	 */
	RESET_PASSWORD,

	/** Vencio por TTL. Lo marca la purga, no un flujo de negocio. */
	EXPIRACION
}
