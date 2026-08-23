package com.akine.organization.domain.port;

import com.akine.organization.domain.Membership;

import java.util.List;

/**
 * Acceso a las memberships. Es el puerto mas caliente del sistema: la resolucion de contexto
 * lo consulta en CADA request, sin cache, porque cachear la vigencia abriria una ventana en la
 * que un acceso revocado sigue funcionando.
 *
 * <p>La vigencia temporal ({@code valid_from} / {@code valid_until}) NO se filtra aca: se
 * evalua con {@code Membership.isValidAt(Instant)}. Asi la regla vive en un solo lugar, se
 * testea sin base y no depende del reloj del motor.
 */
public interface MembershipRepositoryPort {

	Membership save(Membership membership);

	/**
	 * Memberships activas de una cuenta, en cualquier organizacion.
	 *
	 * <p>Es la unica consulta cross-tenant del modulo, y lo es por definicion: responde a que
	 * contextos puede entrar esta persona, pregunta que no ocurre dentro de un tenant.
	 */
	List<Membership> findAllByAccountIdAndActiveTrue(Long accountId);

	/**
	 * Memberships activas de una cuenta en un tenant concreto, de la mas vieja a la mas nueva.
	 *
	 * <p><b>Devuelve una lista y no un {@code Optional}, y eso no es una comodidad.</b> Hasta
	 * la migracion V10, {@code uk_membership_org_account} permitia a lo sumo una fila por
	 * (organizacion, cuenta) —lo que contradice RN-M02-002, que exige que la misma persona
	 * pueda tener roles distintos en consultorios distintos—. Con el unique expandido, un
	 * {@code Optional} se volveria {@code IncorrectResultSizeDataAccessException} en cuanto
	 * apareciera la segunda membership, es decir un 500 en la resolucion de contexto, que corre
	 * en CADA request.
	 *
	 * <p>Cual de las memberships aplica NO lo decide este puerto: depende de la pregunta que se
	 * este haciendo. Los criterios estan en {@code MembershipSelection} y cada llamador
	 * documenta cual usa.
	 */
	List<Membership> findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(
			Long organizationId, Long accountId);

	/** Conteo de uso para {@code MAX_MIEMBROS_ACTIVOS}. Mismas condiciones que el de sedes. */
	long countByOrganizationIdAndActiveTrue(Long organizationId);
}
