package com.akine.organization.application;

import com.akine.organization.domain.Consultorio;
import com.akine.organization.domain.Organization;
import com.akine.organization.domain.Subscription;

/**
 * Las tres entidades que nacen juntas cuando se crea un tenant.
 *
 * <p>Package-private y con entities adentro: es plomeria interna entre servicios de
 * {@code application}, no un tipo de salida. Si fuera publico, la capa {@code api} podria
 * recibirlo y quedaria dependiendo de entities JPA, que
 * {@code entities_no_salen_por_la_api} prohibe.
 *
 * <p>Van juntas porque no tiene sentido que exista una sin las otras: una organizacion sin
 * suscripcion no tiene estado operativo calculable, y una sin consultorio no ofrece ningun
 * contexto de trabajo seleccionable (ADR-0009).
 */
record ProvisionedTenant(
		Organization organization,
		Consultorio consultorio,
		Subscription subscription) {
}
