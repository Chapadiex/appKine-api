package com.akine.organization.spi;

import java.util.List;
import java.util.Optional;

/**
 * Busqueda de una membership por id dentro de un tenant.
 *
 * <p>Filtra SIEMPRE por {@code organizationId}: una membership de otro tenant no resuelve, y
 * quien la pidio recibe 404, nunca 403.
 *
 * <p><b>No confundir con {@code com.akine.platform.spi.tenant.MembershipDirectory}.</b> Ese
 * interfaz —mas viejo, y que se queda con el nombre corto— resuelve la membership de una CUENTA
 * en un contexto (organizacion + consultorio) para la resolucion de sesion en cada request, sin
 * cache, y lo implementa {@code organization.infrastructure.tenant.OrganizationMembershipDirectory}.
 * Este interfaz resuelve una membership YA IDENTIFICADA por su id, y no toma ninguna decision de
 * autenticacion: lo consumen los modulos que necesitan saber donde y cuando vale un vinculo
 * puntual (proyeccion de disponibilidad, 02.04-07/08). Los dos leen la misma tabla pero
 * responden preguntas distintas y no son intercambiables entre si.
 */
public interface ConsultorioMembershipDirectory {

	Optional<ConsultorioMembershipSnapshot> find(long organizationId, long membershipId);

	/**
	 * Los vinculos activos de una cuenta en la organizacion, en orden estable por id.
	 *
	 * <p>Existe porque hay operaciones donde el ACTOR es el profesional, y lo que las identifica es
	 * su membership y no su cuenta: la misma persona puede ser profesional en un centro y
	 * administrativa en otro. Es la misma razon por la que V23 y V28 guardan {@code membership_id} y
	 * no {@code account_id}.
	 *
	 * <p>Devuelve TODOS los vinculos de la organizacion —incluidos los de alcance organizacion, con
	 * {@code consultorio_id} nulo— y deja el filtro por sede al llamador, que es quien sabe con que
	 * criterio quiere resolverlo. Filtrarlo aca obligaria a pasar la sede y devolveria vacio en el
	 * caso legitimo del vinculo organizacional, que cubre todas.
	 */
	List<ConsultorioMembershipSnapshot> findByAccount(long organizationId, long accountId);
}
