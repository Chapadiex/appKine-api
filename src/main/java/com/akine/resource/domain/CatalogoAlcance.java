package com.akine.resource.domain;

/**
 * Quien es el duenio de un concepto del catalogo, que es la distincion que sostiene toda la
 * etapa AKINE-02.05.
 *
 * <p>No es una columna: se deriva de {@code organization_id}. Publicarlo igual, como valor
 * explicito en el contrato y como filtro de las consultas, evita que el cliente tenga que
 * deducirlo de la ausencia de un campo — y "no vino organizationId" es exactamente el tipo de
 * senal que un cliente interpreta mal.
 */
public enum CatalogoAlcance {

	/**
	 * Del catalogo de plataforma: {@code organization_id IS NULL}. Lo ven todos los tenants y
	 * lo administra unicamente el administrador de plataforma. Un tenant que necesita uno
	 * nuevo lo pide (RF-M06-005).
	 */
	GLOBAL,

	/**
	 * Propio de un tenant: {@code organization_id = <id>}. Solo lo ve y lo administra ese
	 * tenant.
	 */
	ORGANIZACION;

	/** Alcance que corresponde a un duenio concreto, {@code null} incluido. */
	public static CatalogoAlcance de(Long organizationId) {
		return organizationId == null ? GLOBAL : ORGANIZACION;
	}
}
