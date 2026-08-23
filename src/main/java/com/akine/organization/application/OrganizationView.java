package com.akine.organization.application;

import com.akine.organization.domain.OperationalStatus;

/**
 * Proyeccion de lectura de una organizacion.
 *
 * <p>Existe porque la capa {@code api} tiene <b>prohibido</b> depender de una entity JPA
 * (regla {@code entities_no_salen_por_la_api}): serializarla filtraria columnas internas,
 * dispararia lazy loading fuera de transaccion y ataria el contrato HTTP al esquema de la
 * base, convirtiendo cualquier cambio de columna en un breaking change de API. Los servicios
 * de {@code application} devuelven estas proyecciones y {@code api} las mapea a su DTO.
 *
 * <p>{@code operationalStatus} es un valor CALCULADO —deriva de la suscripcion y de la baja
 * logica de la organizacion—, no una columna: si el estado viviera en dos entidades existiria
 * una organizacion "activa" con la suscripcion cancelada y nadie sabria cual gana.
 *
 * @param version version optimista actual. Viaja hasta el cliente porque el PATCH la exige de
 *                vuelta: sin ella, dos ediciones concurrentes se pisan en silencio
 */
public record OrganizationView(
		long id,
		String name,
		String slug,
		String timezone,
		boolean active,
		long version,
		OperationalStatus operationalStatus) {
}
