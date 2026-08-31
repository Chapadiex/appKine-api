package com.akine.scheduling.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Los turnos disponibles de una oferta en una ventana, dia por dia.
 *
 * <p><b>Nunca omite un dia.</b> Es la misma decision que {@code DisponibilidadEfectivaView} y por
 * el mismo motivo: la pantalla dibuja una grilla de fechas, y un dia ausente la deja en blanco sin
 * poder distinguir "no hay turnos" de "no cargue ese dia". El dia sin slots viaja con su motivo.
 *
 * @param timezone zona con la que se convirtieron los instantes. Viaja para que la pantalla pueda
 *                 rotularla y para que un error de huso se vea en la respuesta en vez de tener que
 *                 deducirse de horarios corridos
 */
public record AgendaView(
		long ofertaId,
		long consultorioId,
		String nombreComercial,
		int duracionMinutos,
		String timezone,
		List<DiaDeAgenda> dias) {

	public AgendaView {
		dias = dias == null ? List.of() : List.copyOf(dias);
	}

	/**
	 * @param motivoSinSlots nombre de {@code MotivoSinSlots}, o {@code null} si el dia TIENE slots.
	 *                       Nunca es {@code null} con la lista vacia: un dia en blanco sin
	 *                       explicacion es indistinguible de un error del sistema
	 */
	public record DiaDeAgenda(LocalDate fecha, String motivoSinSlots, List<SlotDisponible> slots) {

		public DiaDeAgenda {
			slots = slots == null ? List.of() : List.copyOf(slots);
		}
	}

	/**
	 * @param hasta        instante de fin, EXCLUSIVO
	 * @param profesionalId membership que lo atiende, o {@code null} si la oferta no requiere
	 *                      profesional
	 * @param espacioId    <b>siempre {@code null} en esta etapa.</b> El motor verifica que exista
	 *                     al menos un espacio habilitado y vigente, pero no clava cual: elegirlo
	 *                     es parte de la reserva y tiene que ocurrir bajo el mismo lock que la
	 *                     crea, o dos busquedas concurrentes prometen el mismo box. Lo llena 05.02
	 * @param cupoLibre    hoy siempre igual a {@code cupoTotal}: no existen los turnos todavia
	 */
	public record SlotDisponible(
			Instant desde,
			Instant hasta,
			Long profesionalId,
			Long espacioId,
			int cupoTotal,
			int cupoLibre) {
	}
}
