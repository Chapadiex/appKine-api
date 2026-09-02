package com.akine.contracting.application;

import com.akine.contracting.domain.Financiador;

import java.time.Instant;

/**
 * Proyeccion de lectura de un financiador. Es lo unico que cruza el borde del servicio de
 * aplicacion: la entity nunca sale (AGENT.md §4, regla 6).
 *
 * @param estado  DERIVADO de {@code active}, no una columna
 * @param version la que hay que reenviar para editar
 */
public record FinanciadorView(
		long id,
		String codigo,
		String nombre,
		String tipo,
		String cuit,
		String emailContacto,
		String telefonoContacto,
		String observaciones,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static FinanciadorView de(Financiador financiador) {
		return new FinanciadorView(
				financiador.getId(),
				financiador.getCodigo(),
				financiador.getNombre(),
				financiador.getTipo().name(),
				financiador.getCuit(),
				financiador.getEmailContacto(),
				financiador.getTelefonoContacto(),
				financiador.getObservaciones(),
				financiador.isActive() ? "ACTIVO" : "INACTIVO",
				financiador.getDeletedAt(),
				financiador.getDeactivationReason(),
				financiador.getVersion());
	}
}
