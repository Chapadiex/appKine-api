package com.akine.organization.domain;

/**
 * Alcance con el que un permiso queda otorgado. Es la interseccion {@code ∩ alcance(membership)}
 * de la formula de la matriz §3.
 *
 * <p>Tener el permiso y tenerlo <b>sobre este recurso</b> son dos preguntas distintas, y la
 * segunda es la que impide la escalada silenciosa: un {@code CONSULTORIO_ADMIN} tiene
 * {@code colaborador:manage}, y eso no lo habilita a tocar colaboradores de otra sede.
 */
public enum PermissionScope {

	/** Toda la plataforma, sin restriccion de tenant. Solo {@code PLATFORM_ADMIN}. */
	GLOBAL,

	/** La organizacion entera del actor, todas sus sedes incluidas. */
	ORGANIZACION,

	/** Unicamente la sede de la membership que otorgo el permiso. */
	CONSULTORIO,

	/** Solo las entidades del propio sujeto (el "Propio" de la matriz §3). */
	OWN,

	/** Solo el catalogo global de plataforma, nunca datos de un tenant. */
	CATALOGO,

	/**
	 * Solo mediante acceso de soporte vigente, con motivo declarado y auditado.
	 *
	 * <p>Es el valor "Soporte" de la matriz §3. Tener {@code PLATFORM_ADMIN} no alcanza: hace
	 * falta ademas un {@code support_access} vigente para ESA organizacion (ADR-0020).
	 */
	SOPORTE,

	/**
	 * Denegado por defecto; solo con acceso de soporte <b>y</b> un permiso clinico explicito.
	 *
	 * <p>Es el valor "Restringido" de la matriz §3, el mas cerrado de todos. En F1 siempre
	 * deniega, porque el permiso clinico que lo acompaña no existe hasta F4.
	 */
	RESTRINGIDO;

	/** Indica si este alcance cubre a toda la organizacion, cualquiera sea la sede. */
	public boolean cubreLaOrganizacion() {
		return this == GLOBAL || this == ORGANIZACION;
	}
}
