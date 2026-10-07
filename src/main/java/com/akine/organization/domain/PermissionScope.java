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
	 * La sede de la membership, <b>recortada a la actividad propia del actor</b>: lo que atendio o
	 * lo que le fue asignado, nunca lo del resto del equipo.
	 *
	 * <p>Es uno de los valores "Limitado" de la matriz §2, con la restriccion que la §4 le define a
	 * la celda "Ver Reportes — {@code PROFESIONAL}": <i>solo reportes de su propia actividad</i>.
	 * Nacio en AKINE-G-1 (DP-15) y hoy lo usa unicamente {@code reporte:read}.
	 *
	 * <p><b>No es {@link #OWN}</b>, y confundirlos seria un error caro: {@code OWN} es el "Propio"
	 * del paciente —las entidades de la persona que es el actor— y depende de un vinculo
	 * cuenta↔persona que no existe. Este es la actividad <b>profesional</b> del actor, y se
	 * resuelve por sus memberships, que si existen.
	 *
	 * <p>El evaluador lo trata como {@link #CONSULTORIO} para decidir si cubre la sede pedida, y
	 * devuelve el nombre en {@code grantedByScope}: <b>el recorte lo hace quien lee el dato</b>,
	 * igual que {@code AuditQueryService} recorta por sede cuando la decision vuelve
	 * {@code CONSULTORIO}. Un llamador que ignore el alcance concede de mas, y por eso su unico
	 * consumidor hoy omite toda seccion que no sepa filtrar.
	 */
	ACTIVIDAD_PROPIA,

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
