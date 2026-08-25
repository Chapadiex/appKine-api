package com.akine.resource.application;

import com.akine.resource.domain.Espacio;

import java.time.Instant;

/**
 * Proyeccion de lectura de un espacio. Es lo unico que cruza el borde del servicio: la entity
 * nunca sale (AGENT.md seccion 4, regla 6).
 *
 * @param estado           DERIVADO de {@code active}, no una columna. Ver {@link Espacio}
 * @param enServicio       si el espacio se ofrece para una reserva AHORA (RN-M04-002). Es
 *                         distinto de {@code estado}: un espacio ACTIVO cuya ventana operativa
 *                         todavia no empezo no se ofrece, y la pantalla tiene que poder decir
 *                         por que
 * @param version          la que hay que reenviar para editar
 */
public record EspacioView(
		long id,
		long organizationId,
		long consultorioId,
		String name,
		String tipo,
		int capacidad,
		String notes,
		Instant validFrom,
		Instant validUntil,
		String estado,
		boolean enServicio,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static EspacioView de(Espacio espacio, Instant at) {
		return new EspacioView(
				espacio.getId(),
				espacio.getOrganizationId(),
				espacio.getConsultorioId(),
				espacio.getName(),
				espacio.getTipo().name(),
				espacio.getCapacidad(),
				espacio.getNotes(),
				espacio.getValidFrom(),
				espacio.getValidUntil(),
				espacio.isActive() ? "ACTIVO" : "INACTIVO",
				espacio.estaEnServicio(at),
				espacio.getDeletedAt(),
				espacio.getDeactivationReason(),
				espacio.getVersion());
	}
}
