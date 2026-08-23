package com.akine.organization.infrastructure;

import com.akine.organization.domain.Subscription;
import com.akine.organization.domain.port.SubscriptionRepositoryPort;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Acceso a la suscripcion de una organizacion. Toda consulta filtra por {@code organizationId}.
 */
public interface SubscriptionRepository extends JpaRepository<Subscription, Long>, SubscriptionRepositoryPort {

	/** Hay exactamente una por organizacion: {@code uk_subscription_organization} lo garantiza. */
	Optional<Subscription> findByOrganizationId(Long organizationId);

	/**
	 * Lee la suscripcion BLOQUEANDO la fila ({@code SELECT ... FOR UPDATE}).
	 *
	 * <p>Es lo que hace real la evaluacion de limites bajo concurrencia. Sin este bloqueo, dos
	 * altas simultaneas leen el mismo conteo de uso, las dos lo comparan contra el mismo
	 * limite, las dos pasan, y el limite se viola en silencio: los uniques de las tablas
	 * consumidoras no ayudan porque un unique restringe valores repetidos, no una CANTIDAD.
	 *
	 * <p>El protocolo correcto es, todo dentro de la MISMA transaccion: bloquear con este
	 * metodo, contar recien despues, decidir, insertar, commitear. Contar antes de abrir la
	 * transaccion es exactamente la carrera que este metodo existe para cerrar.
	 *
	 * <p>El bloqueo alcanza a una sola fila de un solo tenant y la seccion critica es corta.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Subscription s where s.organizationId = :organizationId")
	Optional<Subscription> findByOrganizationIdForUpdate(@Param("organizationId") Long organizationId);
}
