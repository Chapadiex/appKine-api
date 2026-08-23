package com.akine.organization.domain.exception;

import com.akine.organization.domain.SubscriptionStatus;

/**
 * Se intento una mutacion de negocio con la suscripcion suspendida.
 *
 * <p>Suspender bloquea la operacion, jamas destruye datos (RN-M01-002): las lecturas y los
 * endpoints de administracion de organizacion y suscripcion siguen funcionando, y los
 * historicos siguen consultables. Lo unico que se rechaza es dar de alta o modificar
 * informacion de negocio.
 */
public class SubscriptionSuspendedException extends RuntimeException {

	private final Long organizationId;

	public SubscriptionSuspendedException(Long organizationId) {
		super("La suscripcion de la organizacion esta suspendida: operacion de negocio bloqueada");
		this.organizationId = organizationId;
	}

	/**
	 * Variante que nombra el estado real que bloqueo la operacion.
	 *
	 * <p>Existe porque hay operaciones -como el cambio de plan- que exigen la suscripcion
	 * ACTIVA y por lo tanto tambien se rechazan cuando esta CANCELADA. Reportar "suspendida"
	 * en ese caso manda al que diagnostica a buscar una suspension que nunca ocurrio.
	 *
	 * <p>El status HTTP no cambia: ambos casos son 409 (regla de negocio incumplida). Lo que
	 * cambia es que el log dice la verdad.
	 */
	public SubscriptionSuspendedException(Long organizationId, SubscriptionStatus status) {
		super("La suscripcion de la organizacion esta " + status
				+ ": operacion de negocio bloqueada");
		this.organizationId = organizationId;
	}

	public Long getOrganizationId() {
		return organizationId;
	}
}
