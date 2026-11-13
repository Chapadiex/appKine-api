package com.akine.resource.application;

import com.akine.resource.domain.IntervaloLocal;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Una franja vigente de un profesional que el horario nuevo de la sede deja afuera, total o
 * parcialmente (regla del 08/11/2026).
 *
 * @param resultantes la parte que queda dentro del horario nuevo; vacia si queda entera afuera,
 *                    varias si la sede cierra en el medio de la franja
 * @param accion      lo que se hizo con ella; {@code null} en el aviso, antes de decidir
 */
public record FranjaFueraDeHorario(
		long bloqueId,
		long membershipId,
		String profesionalNombre,
		int diaSemana,
		LocalTime horaDesde,
		LocalTime horaHasta,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		List<IntervaloLocal> resultantes,
		AccionSobreFranja accion) {

	/** Que paso con la franja al guardar el horario de la sede. */
	public enum AccionSobreFranja {
		/** Quedo en su parte comun con el horario de la sede. */
		RECORTADA,
		/** No tenia parte comun: se dio de baja (baja logica, con motivo). */
		DADA_DE_BAJA,
		/** Se guardo el horario sin tocarla: queda para ajustarla a mano. */
		CONSERVADA
	}

	public FranjaFueraDeHorario {
		resultantes = List.copyOf(resultantes);
	}

	FranjaFueraDeHorario con(AccionSobreFranja nueva) {
		return new FranjaFueraDeHorario(bloqueId, membershipId, profesionalNombre, diaSemana, horaDesde, horaHasta,
				vigenciaDesde, vigenciaHasta, resultantes, nueva);
	}
}
