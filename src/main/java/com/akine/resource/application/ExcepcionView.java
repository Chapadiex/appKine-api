package com.akine.resource.application;

import com.akine.resource.domain.DisponibilidadExcepcion;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Proyeccion de lectura de una excepcion de disponibilidad. Es lo unico que cruza el borde del
 * servicio: la entity nunca sale (AGENT.md seccion 4, regla 6).
 *
 * @param membershipId {@code null} = alcance SEDE ENTERA. La pantalla tiene que mostrarlo
 *                     distinto: una excepcion de sede afecta a todos los profesionales, y en un
 *                     feriado una APERTURA de sede reemplaza el horario base de todos ellos
 * @param horaDesde    {@code null} junto con {@code horaHasta} significa DIA COMPLETO
 * @param estado       DERIVADO de {@code active}, no una columna
 * @param version      la que hay que reenviar para editar
 */
public record ExcepcionView(
		long id,
		long organizationId,
		long consultorioId,
		Long membershipId,
		String tipo,
		String motivo,
		LocalDate fechaDesde,
		LocalDate fechaHasta,
		LocalTime horaDesde,
		LocalTime horaHasta,
		Long feriadoId,
		String notes,
		String estado,
		Instant deletedAt,
		String deactivationReason,
		long version) {

	public static ExcepcionView de(DisponibilidadExcepcion excepcion) {
		return new ExcepcionView(
				excepcion.getId(),
				excepcion.getOrganizationId(),
				excepcion.getConsultorioId(),
				excepcion.getMembershipId(),
				excepcion.getTipo().name(),
				excepcion.getMotivo().name(),
				excepcion.getFechaDesde(),
				excepcion.getFechaHasta(),
				excepcion.getHoraDesde(),
				excepcion.getHoraHasta(),
				excepcion.getFeriadoId(),
				excepcion.getNotes(),
				excepcion.isActive() ? "ACTIVO" : "INACTIVO",
				excepcion.getDeletedAt(),
				excepcion.getDeactivationReason(),
				excepcion.getVersion());
	}
}
