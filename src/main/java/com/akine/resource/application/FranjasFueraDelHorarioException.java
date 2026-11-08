package com.akine.resource.application;

import java.util.List;

/**
 * El horario nuevo de la sede deja afuera franjas de profesionales y el pedido no dijo que hacer
 * con ellas (409 {@code franjas-de-profesionales-fuera-del-horario}).
 *
 * <p>Es el aviso, no un bloqueo: no se guardo nada, y el mismo pedido con
 * {@code franjasFueraDelHorario = RECORTAR} o {@code CONSERVAR} procede. Mismo criterio que la
 * edicion de un bloque con turnos afectados: un cambio que deja algo afuera no se guarda sin que
 * nadie lo haya visto.
 */
public class FranjasFueraDelHorarioException extends RuntimeException {

	private final List<FranjaFueraDeHorario> franjas;

	public FranjasFueraDelHorarioException(List<FranjaFueraDeHorario> franjas) {
		super(franjas.size() == 1
				? "El horario nuevo de la sede deja afuera una franja de un profesional. Elegi si recortarla "
						+ "al horario nuevo o conservarla para ajustarla a mano."
				: "El horario nuevo de la sede deja afuera " + franjas.size() + " franjas de profesionales. "
						+ "Elegi si recortarlas al horario nuevo o conservarlas para ajustarlas a mano.");
		this.franjas = List.copyOf(franjas);
	}

	public List<FranjaFueraDeHorario> getFranjas() {
		return franjas;
	}
}
