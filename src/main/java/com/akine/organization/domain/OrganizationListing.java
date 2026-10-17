package com.akine.organization.domain;

import java.time.Instant;

/**
 * Una fila del listado de organizaciones de la administracion de plataforma.
 *
 * <p>Es una proyeccion de lectura, no una entidad: junta la organizacion con su suscripcion y su
 * plan en una sola consulta. Lleva solo lo que la plataforma necesita para elegir un centro
 * —nombre, slug, estado, plan y alta— y nada que sea dato del centro: ni miembros, ni sedes, ni
 * consumo de limites, ni pacientes.
 */
public record OrganizationListing(
		long id,
		String name,
		String slug,
		SubscriptionStatus status,
		String planCode,
		String planName,
		Instant createdAt) {
}
