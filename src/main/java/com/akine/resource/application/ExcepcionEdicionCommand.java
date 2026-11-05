package com.akine.resource.application;

import com.akine.resource.domain.MotivoExcepcion;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Edicion de una excepcion de disponibilidad (checklist de agenda §2.5). Reemplaza los datos: es un
 * {@code PUT}, y la pantalla manda la excepcion entera como la muestra.
 *
 * <p><b>Ni el tipo ni el alcance se editan.</b> Pasar un cierre a apertura, o el de un profesional a la
 * sede entera, no es corregir el mismo hecho sino cargar otro: se da de baja y se carga de nuevo.
 *
 * @param horaDesde               {@code null} junto con {@code horaHasta} = dia completo
 * @param notes                   obligatorias con motivo {@code OTRO}
 * @param expectedVersion         version que el cliente leyo
 * @param confirmaTurnosAfectados {@code true} para guardar aunque el cambio deje turnos pendientes
 *                                fuera de la disponibilidad. Sin esto, 409 con la lista
 */
public record ExcepcionEdicionCommand(
		MotivoExcepcion motivo,
		LocalDate fechaDesde,
		LocalDate fechaHasta,
		LocalTime horaDesde,
		LocalTime horaHasta,
		String notes,
		long expectedVersion,
		boolean confirmaTurnosAfectados,
		boolean sustituirDisponibilidadSuperpuesta) {

	/** Sin sustitucion (disponibilidad central, 0.158.0). */
	public ExcepcionEdicionCommand(MotivoExcepcion motivo, LocalDate fechaDesde, LocalDate fechaHasta,
			LocalTime horaDesde, LocalTime horaHasta, String notes, long expectedVersion,
			boolean confirmaTurnosAfectados) {
		this(motivo, fechaDesde, fechaHasta, horaDesde, horaHasta, notes, expectedVersion, confirmaTurnosAfectados,
				false);
	}
}
