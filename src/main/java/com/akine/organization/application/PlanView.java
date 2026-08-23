package com.akine.organization.application;

import com.akine.organization.spi.FeatureCode;

import java.util.List;

/**
 * Un plan del catalogo con lo que incluye.
 *
 * <p>Se arma con las filas ACTIVAS de {@code plan_limit} y {@code plan_feature}: las dadas de
 * baja siguen en la tabla para poder reconstruir que incluia el plan cuando alguien se
 * suscribio, pero no forman parte de la oferta de hoy.
 */
public record PlanView(
		long id,
		String code,
		String name,
		List<PlanLimitView> limits,
		List<FeatureCode> features) {

	public PlanView {
		limits = List.copyOf(limits);
		features = List.copyOf(features);
	}
}
