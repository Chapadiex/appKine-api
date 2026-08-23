package com.akine.organization.domain.port;

import com.akine.organization.domain.SubscriptionTransition;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Acceso al historico de la suscripcion. <b>Solo lectura y alta.</b>
 *
 * <p>No existe ningun metodo de modificacion ni de borrado, y esa ausencia es la garantia:
 * append-only escrito en un comentario SQL que nadie hace cumplir es una intencion, no una
 * garantia. Junto con la falta de setters en la entity, satisface RN-M01-002 —suspender o
 * cambiar de plan jamas destruye historicos—.
 */
public interface SubscriptionTransitionRepositoryPort {

	/** Unica escritura permitida: agregar una fila. */
	SubscriptionTransition save(SubscriptionTransition transition);

	/** Historico paginado de una suscripcion, del hecho mas reciente al mas viejo. */
	Page<SubscriptionTransition> findAllByOrganizationIdAndSubscriptionIdOrderByOccurredAtDesc(
			Long organizationId, Long subscriptionId, Pageable pageable);
}
