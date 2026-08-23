package com.akine.organization.infrastructure;

import com.akine.organization.domain.OrganizationOnboarding;
import com.akine.organization.domain.port.OrganizationOnboardingRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Acceso al registro de idempotencia del alta compuesta (ADR-0008).
 *
 * <p>Protocolo del reintento, y el orden importa: se intenta INSERTAR y se deja que
 * {@code uk_onboarding_key} decida. Si viola el unique, se relee por clave y se devuelve el
 * resultado del ganador. Resolverlo con un SELECT previo y nada mas seria la misma carrera
 * con otro nombre: dos hilos concurrentes con la misma clave leen ambos "no existe".
 *
 * <p>Por eso hace falta {@code saveAndFlush}: la violacion del unique tiene que manifestarse
 * dentro del bloque que la captura, no al final de la transaccion.
 */
public interface OrganizationOnboardingRepository extends JpaRepository<OrganizationOnboarding, Long>, OrganizationOnboardingRepositoryPort {

	Optional<OrganizationOnboarding> findByIdempotencyKey(String idempotencyKey);
}
