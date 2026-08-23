package com.akine.organization.infrastructure;

import com.akine.organization.domain.Membership;
import com.akine.organization.domain.port.MembershipRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Acceso a las memberships. Es el repositorio mas caliente del sistema: la resolucion de
 * contexto lo consulta en CADA request, sin cache.
 *
 * <p>Por que sin cache: cachear la vigencia de una membership abre una ventana en la que un
 * acceso revocado sigue funcionando. El indice {@code ix_membership_account} hace que la
 * consulta sea un seek, y ese costo es preferible a una ventana de revocacion.
 *
 * <p>La vigencia temporal ({@code valid_from} / {@code valid_until}) NO se filtra en SQL: se
 * evalua con {@code Membership.isValidAt(Instant)}. Asi la regla vive en un solo lugar,
 * testeable sin base, y la consulta no depende del reloj del motor.
 */
public interface MembershipRepository extends JpaRepository<Membership, Long>, MembershipRepositoryPort {

	/**
	 * Memberships activas de una cuenta, en cualquier organizacion.
	 *
	 * <p>Es cross-tenant a proposito y es la unica consulta que lo es: responde "a que
	 * contextos puede entrar esta persona", una pregunta que por definicion no ocurre dentro
	 * de un tenant. El llamador filtra despues por vigencia.
	 */
	List<Membership> findAllByAccountIdAndActiveTrue(Long accountId);

	/** Memberships activas de una cuenta en un tenant concreto. Ver el puerto: desde V10 pueden ser varias. */
	List<Membership> findAllByOrganizationIdAndAccountIdAndActiveTrueOrderByIdAsc(
			Long organizationId, Long accountId);

	List<Membership> findAllByOrganizationIdAndActiveTrue(Long organizationId);

	/**
	 * Conteo de uso para el limite MAX_MIEMBROS_ACTIVOS. Mismas condiciones que
	 * {@code ConsultorioRepository.countByOrganizationIdAndActiveTrue}: dentro de la
	 * transaccion y despues del bloqueo de la suscripcion.
	 */
	long countByOrganizationIdAndActiveTrue(Long organizationId);

	/**
	 * Chequeo previo de pertenencia. Sin llamadores hoy.
	 *
	 * <p>Ya NO es el chequeo previo de un unique: desde V10 la restriccion es
	 * {@code uk_membership_org_account_scope} y una cuenta puede tener varias memberships en la
	 * misma organizacion, una por alcance. Responde "esta cuenta tiene alguna membership aca",
	 * que es otra pregunta.
	 */
	boolean existsByOrganizationIdAndAccountId(Long organizationId, Long accountId);
}
