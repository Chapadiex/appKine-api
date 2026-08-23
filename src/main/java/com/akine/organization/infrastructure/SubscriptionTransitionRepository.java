package com.akine.organization.infrastructure;

import com.akine.organization.domain.SubscriptionTransition;
import com.akine.organization.domain.port.SubscriptionTransitionRepositoryPort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Acceso al historico de la suscripcion. <b>Solo lectura y alta.</b>
 *
 * <p>Extiende {@link Repository} y no {@code JpaRepository} deliberadamente: heredar
 * {@code JpaRepository} traeria {@code delete}, {@code deleteAll} y {@code saveAll} sobre una
 * tabla declarada append-only, y "append-only" escrito en un comentario SQL que nadie hace
 * cumplir es una intencion, no una garantia. Aca no existe ningun metodo de modificacion, asi
 * que no hay forma de borrar historia sin escribir SQL a mano.
 *
 * <p>Junto con la ausencia de setters en {@link SubscriptionTransition}, esto satisface
 * RN-M01-002: suspender o cambiar de plan jamas destruye historicos.
 *
 * <p>Todas las consultas filtran por {@code organizationId}: el historico de un tenant no se
 * lee desde otro.
 */
public interface SubscriptionTransitionRepository extends Repository<SubscriptionTransition, Long>, SubscriptionTransitionRepositoryPort {

	/** Unica escritura permitida: agregar una fila. */
	SubscriptionTransition save(SubscriptionTransition transition);

	Optional<SubscriptionTransition> findById(Long id);

	/** Historico paginado de una suscripcion, del hecho mas reciente al mas viejo. */
	Page<SubscriptionTransition> findAllByOrganizationIdAndSubscriptionIdOrderByOccurredAtDesc(
			Long organizationId, Long subscriptionId, Pageable pageable);

	/** Historico completo de un tenant. Para volumenes acotados y para los tests. */
	List<SubscriptionTransition> findAllByOrganizationIdOrderByOccurredAtDesc(Long organizationId);
}
