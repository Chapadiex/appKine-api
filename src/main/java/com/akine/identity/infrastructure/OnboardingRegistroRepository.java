package com.akine.identity.infrastructure;

import com.akine.identity.domain.OnboardingRegistro;
import com.akine.identity.domain.port.OnboardingRegistroRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Acceso al registro de idempotencia del alta self-service (ADR-0008).
 *
 * <p>Quien garantiza el "no duplicar" es {@code uk_onboarding_registro_clave}, no este
 * {@code findBy}: el SELECT es la ruta feliz del reintento y el unique es la garantia cuando
 * dos hilos con la misma clave llegan a la vez.
 */
public interface OnboardingRegistroRepository
		extends JpaRepository<OnboardingRegistro, Long>, OnboardingRegistroRepositoryPort {

	@Override
	Optional<OnboardingRegistro> findByClaveIdempotencia(String claveIdempotencia);
}
