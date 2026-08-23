package com.akine.identity.domain.port;

import com.akine.identity.domain.OnboardingRegistro;

import java.util.Optional;

/**
 * Acceso al registro de idempotencia del alta self-service (ADR-0008).
 *
 * <p>Protocolo del reintento, y el orden importa: se intenta INSERTAR y se deja que
 * {@code uk_onboarding_registro_clave} decida. Si viola el unique, se relee por clave. Un
 * SELECT previo sin la restriccion detras seria la misma carrera con otro nombre.
 */
public interface OnboardingRegistroRepositoryPort {

	Optional<OnboardingRegistro> findByClaveIdempotencia(String claveIdempotencia);

	/**
	 * Inserta forzando el flush: la violacion del unique tiene que manifestarse dentro del
	 * bloque que la captura, no al final de la transaccion.
	 */
	OnboardingRegistro saveAndFlush(OnboardingRegistro registro);
}
