package com.akine.organization.application;

import com.akine.organization.domain.SubscriptionStatus;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Proyeccion de lectura de la suscripcion de una organizacion, con su plan y su consumo.
 *
 * <p>{@code allowedTargets} viene del backend y no lo deduce la UI (RNF-M01-008): si el
 * frontend replicara la tabla de transiciones, tendriamos la misma regla en dos lugares y
 * empezaria a divergir en la primera correccion.
 *
 * @param version version optimista. El cliente la devuelve al pedir un cambio de plan
 */
public record SubscriptionView(
		long id,
		long organizationId,
		String planCode,
		String planName,
		SubscriptionStatus status,
		Instant startedAt,
		long version,
		List<LimitUsageView> limits,
		Set<SubscriptionStatus> allowedTargets) {

	public SubscriptionView {
		limits = List.copyOf(limits);
		allowedTargets = Set.copyOf(allowedTargets);
	}
}
