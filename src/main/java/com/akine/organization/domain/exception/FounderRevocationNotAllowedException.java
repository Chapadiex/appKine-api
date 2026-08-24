package com.akine.organization.domain.exception;

/**
 * Un administrador que no es el fundador intento desvincular al fundador.
 *
 * <p>El invariante se lo asigno a esta etapa el comentario de {@code is_founder} en V3. La
 * condicion de fundador es un ATRIBUTO de la membership, no un rol (RN-M05-005/006): el rol del
 * propietario sigue siendo {@code ORG_ADMIN}.
 *
 * <p><b>403 y no 409</b>, a diferencia de los otros dos invariantes de esta familia: no es una
 * restriccion sobre el ESTADO en el que quedaria el tenant, es una restriccion sobre QUIEN
 * puede hacerlo. El fundador si puede revocarse a si mismo —sujeto al invariante de ultimo
 * admin y al de self-revoke— y un {@code PLATFORM_ADMIN} con acceso de soporte vigente tambien
 * puede, y queda auditado con {@code SUPPORT_ACCESS_USED}.
 */
public class FounderRevocationNotAllowedException extends RuntimeException {

	private final Long membershipId;

	public FounderRevocationNotAllowedException(Long membershipId) {
		super("La membership del fundador no puede ser desvinculada por otro administrador");
		this.membershipId = membershipId;
	}

	public Long getMembershipId() {
		return membershipId;
	}
}
