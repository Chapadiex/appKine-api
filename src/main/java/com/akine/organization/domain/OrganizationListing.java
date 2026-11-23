package com.akine.organization.domain;

import com.akine.organization.spi.Servicio;

import java.time.Instant;
import java.util.Set;

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
		boolean ia,
		Set<Servicio> servicios) {

	/** La que arma la consulta; la IA y los servicios se completan despues, para toda la pagina junta. */
	public OrganizationListing(long id, String name, String slug, SubscriptionStatus status, String planCode,
			String planName, Instant createdAt, Instant pruebaHasta, Instant pagadoHasta) {
		this(id, name, slug, status, planCode, planName, createdAt, pruebaHasta, pagadoHasta, false, Set.of());
	}

	/** Sin control de cobro. */
	public OrganizationListing(long id, String name, String slug, SubscriptionStatus status, String planCode,
			String planName, Instant createdAt, Instant pruebaHasta) {
		this(id, name, slug, status, planCode, planName, createdAt, pruebaHasta, null, false, Set.of());
	}

	public OrganizationListing conContratacion(boolean tieneIa, Set<Servicio> contratados) {
		return new OrganizationListing(id, name, slug, status, planCode, planName, createdAt, pruebaHasta,
				pagadoHasta, tieneIa, contratados);
	}
}
