package com.akine.organization.domain.exception;

/**
 * El slug pedido para la organizacion ya lo usa otro tenant.
 *
 * <p>El slug es unico GLOBAL —se usa en URLs y en soporte— asi que esta colision no tiene
 * alcance tenant y no se puede resolver eligiendo otro por el cliente sin avisarle: quien
 * mando un slug explicito quiere ESE. Se responde {@code 409} y se le pide otro.
 *
 * <p><b>Por que es una excepcion propia y no la {@code DataIntegrityViolationException} cruda.</b>
 * Distinguir la causa importa dos veces. Una, porque el alta compuesta puede chocar contra dos
 * uniques distintos —{@code uk_organization_slug} y {@code uk_onboarding_key}— y cada uno exige
 * una respuesta diferente: el primero es un error del cliente, el segundo es una carrera cuyo
 * desenlace correcto es el mismo 202 del ganador. Confundirlos, como se hacia antes, devolvia
 * un 500 en el peor caso y un mensaje equivocado en el mejor. Y dos, porque el mensaje crudo
 * trae el SQL y el nombre del indice, que nunca pueden salir en una respuesta.
 *
 * <p>No lleva el slug al cuerpo de la respuesta: el cliente ya lo tiene, y reflejar en la
 * respuesta lo que mando el cliente es el vector clasico de XSS reflejado. Va al log.
 */
public class OrganizationSlugTakenException extends RuntimeException {

	private final transient String slug;

	public OrganizationSlugTakenException(String slug) {
		super("El identificador legible de la organizacion ya esta en uso");
		this.slug = slug;
	}

	/** Para el log correlacionado. */
	public String getSlug() {
		return slug;
	}
}
