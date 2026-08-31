package com.akine.scheduling.domain;

import java.time.LocalDate;
import java.util.List;

/**
 * Un dia de agenda ya resuelto: o tiene slots, o tiene un motivo por el que no.
 *
 * <p>Los dos estados son excluyentes por construccion. Un dia con slots y motivo seria una
 * contradiccion; un dia sin slots y sin motivo es el que la pantalla no puede explicar, que es
 * justo lo que el criterio de aceptacion pide evitar.
 */
public record DiaDeSlots(LocalDate fecha, MotivoSinSlots motivo, List<Slot> slots) {

	public DiaDeSlots {
		slots = slots == null ? List.of() : List.copyOf(slots);
		if (!slots.isEmpty() && motivo != null) {
			throw new IllegalArgumentException(
					"Un dia con slots no lleva motivo: " + fecha + " tiene " + slots.size()
							+ " slots y motivo " + motivo);
		}
		if (slots.isEmpty() && motivo == null) {
			throw new IllegalArgumentException(
					"Un dia sin slots exige un motivo que lo explique: " + fecha);
		}
	}

	public static DiaDeSlots sin(LocalDate fecha, MotivoSinSlots motivo) {
		return new DiaDeSlots(fecha, motivo, List.of());
	}

	public static DiaDeSlots con(LocalDate fecha, List<Slot> slots) {
		return new DiaDeSlots(fecha, null, slots);
	}
}
