package com.akine.organization.domain.exception;

import com.akine.organization.domain.SubscriptionStatus;

/**
 * Se intento registrar un cobro sobre una suscripcion SUSPENDIDA o CANCELADA guardada
 * (19/11/2026, docs/diseno/AKINE-cobro-mensual.md). La suspension manual de la plataforma manda
 * sobre el cobro: primero se reactiva por la transicion de siempre y despues se cobra.
 */
public class CobroNoAdmitidoException extends RuntimeException {

	private final Long organizationId;
	private final SubscriptionStatus status;

	public CobroNoAdmitidoException(Long organizationId, SubscriptionStatus status) {
		super("La suscripcion de la organizacion esta " + status + ": no admite registrar un cobro");
		this.organizationId = organizationId;
		this.status = status;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public SubscriptionStatus getStatus() {
		return status;
	}
}
