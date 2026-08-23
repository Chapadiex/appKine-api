package com.akine.organization.domain.port;

import com.akine.organization.domain.Membership;

import java.util.List;
import java.util.Optional;

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

	/** La membership de una cuenta en un tenant concreto. */
	Optional<Membership> findByOrganizationIdAndAccountIdAndActiveTrue(
			Long organizationId, Long accountId);

	/** Conteo de uso para {@code MAX_MIEMBROS_ACTIVOS}. Mismas condiciones que el de sedes. */
	long countByOrganizationIdAndActiveTrue(Long organizationId);
}
