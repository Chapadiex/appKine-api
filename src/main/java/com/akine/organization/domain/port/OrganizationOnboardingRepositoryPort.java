package com.akine.organization.domain.port;

import com.akine.organization.domain.OrganizationOnboarding;

import java.util.Optional;

/**
 * Acceso al registro de idempotencia del alta compuesta (ADR-0008).
 *
 * <p>Protocolo del reintento, y el orden importa: se intenta INSERTAR y se deja que
 * {@code uk_onboarding_key} decida. Si viola el unique, se relee por clave y se devuelve el
 * resultado del ganador. Resolverlo con un SELECT previo y nada mas seria la misma carrera con
 * otro nombre: dos hilos concurrentes con la misma clave leen ambos que no existe.
 */
public interface OrganizationOnboardingRepositoryPort {

	Optional<OrganizationOnboarding> findByIdempotencyKey(String idempotencyKey);

	/**
	 * Inserta forzando el flush.
	 *
	 * <p>El flush no es opcional: la violacion del unique tiene que manifestarse DENTRO del
	 * bloque que la captura, no al final de la transaccion, cuando ya no hay a quien avisarle.
	 */
	OrganizationOnboarding saveAndFlush(OrganizationOnboarding onboarding);
}
