package com.akine.identity.domain;

/**
 * Desenlace de un alta self-service.
 *
 * <p>No hay estado intermedio observable a proposito: todo el alta ocurre en UNA transaccion
 * (ADR-0008), asi que o quedo escrita entera o no quedo nada.
 */
public enum EstadoOnboarding {

	/** Se creo la cuenta y se aprovisiono el tenant. */
	COMPLETADO,

	/**
	 * El email ya tenia cuenta, asi que no se creo nada y se encolo un aviso de "ya tenes
	 * cuenta". La respuesta al cliente es la MISMA que la del caso anterior: el registro no
	 * puede convertirse en un oraculo de direcciones validas.
	 */
	DUPLICADO
}
