package com.akine.organization.spi;

/**
 * Alta de memberships desde otro modulo.
 *
 * <p>Existe por una restriccion de ownership, no por gusto: el alta directa necesita partir de
 * un <b>email</b> —es lo que un administrador tipea— y {@code cuenta} es de {@code identity}.
 * {@code organization} no puede compilar contra {@code identity} (regla heredada de 01.01,
 * verificada por ArchUnit), asi que la traduccion email &rarr; {@code accountId} tiene que
 * ocurrir del lado de {@code identity}, que despues entra por aca con el id ya resuelto. Es la
 * misma direccion que ya usa el onboarding compuesto:
 * {@code identity -> organization.spi}, nunca al reves.
 *
 * <p>La consecuencia practica para quien construya la capa {@code api}: <b>el endpoint de alta
 * directa vive en {@code identity.api}</b>, no en {@code organization.api}. Un controller de
 * {@code organization} no puede resolver el email y recibir el {@code accountId} desde el
 * cliente seria dejar que el cliente elija a quien vincular por id.
 */
public interface MembershipProvisioning {

	/**
	 * Crea una membership sobre una cuenta existente.
	 *
	 * <p>Autoriza, valida los invariantes y audita, todo dentro de una transaccion que empieza
	 * bloqueando la fila de {@code subscription} del tenant (el orden de bloqueo unico del
	 * sistema, {@code subscription -> organization}).
	 *
	 * @param actorAccountId       quien da el alta
	 * @param actorPlatformAdmin   si el actor administra la plataforma
	 * @param organizationId       tenant, tomado del contexto validado del request
	 * @param command              datos del vinculo
	 * @return el id de la membership creada
	 * @throws com.akine.organization.domain.exception.PermissionDeniedException si al actor le
	 *         falta {@code colaborador:manage} en el alcance pedido (403)
	 * @throws com.akine.organization.domain.exception.OrganizationNotFoundException si el tenant
	 *         o la sede no son alcanzables desde su contexto (404)
	 * @throws com.akine.organization.domain.exception.MembershipAlreadyExistsException si esa
	 *         cuenta ya tiene un vinculo con ese alcance, vigente o historico (409)
	 */
	long createDirect(
			long actorAccountId,
			boolean actorPlatformAdmin,
			long organizationId,
			DirectMembershipCommand command);
}
