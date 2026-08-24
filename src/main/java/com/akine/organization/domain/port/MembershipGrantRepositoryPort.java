package com.akine.organization.domain.port;

import com.akine.organization.domain.MembershipGrant;
import com.akine.organization.domain.PermissionCode;

import java.util.List;
import java.util.Optional;

/**
 * Acceso a los permisos adicionales de una membership.
 *
 * <p>La vigencia temporal NO se filtra en SQL, igual que en {@code MembershipRepositoryPort}:
 * se evalua con {@code MembershipGrant.isValidAt(Instant)}. Asi la regla vive en un solo lugar,
 * se testea sin base y no depende del reloj del motor, que puede no ser el del backend.
 */
public interface MembershipGrantRepositoryPort {

	MembershipGrant save(MembershipGrant grant);

	/**
	 * Persiste y sincroniza con la base de inmediato.
	 *
	 * <p><b>Existe para que la clave duplicada sea un 409 y no un 500.</b> Sin el flush
	 * explicito, la violacion del unique aparece recien al commit, fuera del alcance del
	 * {@code catch} del servicio, y el advice generico la mapea a 500. Con el flush, el
	 * servicio traduce la {@code DataIntegrityViolationException} a una excepcion de dominio y
	 * la deja propagar — y no vuelve a tocar la sesion JPA, porque una sesion reusada despues
	 * de un flush fallido tira {@code AssertionFailure}.
	 */
	MembershipGrant saveAndFlush(MembershipGrant grant);

	/** Grants activos de una membership. El llamador filtra por vigencia. */
	List<MembershipGrant> findAllByMembershipIdAndActiveTrue(Long membershipId);

	/** Grants activos de varias memberships de una vez: evita el N+1 del evaluador. */
	List<MembershipGrant> findAllByMembershipIdInAndActiveTrue(List<Long> membershipIds);

	/** El grant activo de un permiso concreto, si lo hay. El unique garantiza que sea uno solo. */
	Optional<MembershipGrant> findByMembershipIdAndPermissionCodeAndActiveTrue(
			Long membershipId, PermissionCode permissionCode);
}
