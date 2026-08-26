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
 * @param nuevo        {@code true} SOLO cuando este pedido creo la fila. Existe para que la capa
 *                     {@code api} pueda responder <b>201</b> en el alta real y <b>200</b> en el
 *                     alta idempotente sin volver a consultar la base: la vista de la fila que ya
 *                     existia es indistinguible de la de la recien creada. En toda lectura vale
 *                     {@code false} y <b>no viaja en el contrato</b>: lo que el cliente ve es el
 *                     codigo HTTP
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
		long version,
		boolean nuevo) {

	/** Lectura, baja, o el alta IDEMPOTENTE que devolvio la fila que ya existia. */
	public static ExcepcionView de(DisponibilidadExcepcion excepcion) {
		return construir(excepcion, false);
	}

	/** Vista de una excepcion que ESTE pedido acaba de crear. El controller la traduce a 201. */
	public static ExcepcionView nueva(DisponibilidadExcepcion excepcion) {
		return construir(excepcion, true);
	}

	private static ExcepcionView construir(DisponibilidadExcepcion excepcion, boolean nuevo) {
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
				excepcion.getVersion(),
				nuevo);
	}
}
