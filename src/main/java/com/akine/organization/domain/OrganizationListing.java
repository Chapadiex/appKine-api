package com.akine.organization.domain;

import java.time.Instant;

/**
 * Una fila del listado de organizaciones de la administracion de plataforma.
 *
 * <p>Es una proyeccion de lectura, no una entidad: junta la organizacion con su suscripcion y su
 * plan en una sola consulta. Lleva solo lo que la plataforma necesita para elegir un centro
 * —nombre, slug, estado, plan, alta y cobro— y nada que sea dato del centro: ni miembros, ni
 * sedes, ni consumo de limites, ni pacientes.
 */
public record OrganizationListing(
		long id,
		String name,
		String slug,
		SubscriptionStatus status,
		String planCode,
		String planName,
		Instant createdAt,
		Instant pruebaHasta,
		Instant pagadoHasta,
		boolean ia) {

	/** La que arma la consulta; si tiene IA se completa despues, para toda la pagina junta. */
	public OrganizationListing(long id, String name, String slug, SubscriptionStatus status, String planCode,
			String planName, Instant createdAt, Instant pruebaHasta, Instant pagadoHasta) {
		this(id, name, slug, status, planCode, planName, createdAt, pruebaHasta, pagadoHasta, false);
	}

	/** Sin control de cobro. */
	public OrganizationListing(long id, String name, String slug, SubscriptionStatus status, String planCode,
			String planName, Instant createdAt, Instant pruebaHasta) {
		this(id, name, slug, status, planCode, planName, createdAt, pruebaHasta, null, false);
	}

	public OrganizationListing conIa(boolean tieneIa) {
		return new OrganizationListing(id, name, slug, status, planCode, planName, createdAt, pruebaHasta,
				pagadoHasta, tieneIa);
	}
}
