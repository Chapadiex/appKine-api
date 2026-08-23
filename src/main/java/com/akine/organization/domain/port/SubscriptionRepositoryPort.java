package com.akine.organization.domain.port;

import com.akine.organization.domain.Subscription;

import java.util.Optional;

/**
 * Acceso a la suscripcion de una organizacion. Hay exactamente una por tenant.
 */
public interface SubscriptionRepositoryPort {

	Subscription save(Subscription subscription);

	/** Lectura normal, sin bloqueo: para mostrar estado y para decisiones que no cuentan. */
	Optional<Subscription> findByOrganizationId(Long organizationId);

	/**
	 * Lee la suscripcion BLOQUEANDO la fila ({@code SELECT ... FOR UPDATE}).
	 *
	 * <p>Es lo que hace real la evaluacion de limites bajo concurrencia. Sin este bloqueo, dos
	 * altas simultaneas leen el mismo conteo, las dos lo comparan contra el mismo limite, las
	 * dos pasan, y el limite se viola en silencio: los uniques de las tablas consumidoras no
	 * ayudan porque un unique restringe valores repetidos, no una CANTIDAD.
	 *
	 * <p>Protocolo, todo en la MISMA transaccion: bloquear con este metodo, contar recien
	 * despues, decidir, insertar, commitear. El bloqueo alcanza a una fila de un solo tenant y
	 * la seccion critica es corta.
	 */
	Optional<Subscription> findByOrganizationIdForUpdate(Long organizationId);
}
