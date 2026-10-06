package com.akine.scheduling.application;

import com.akine.scheduling.domain.AlcanceDeSerie;
import com.akine.scheduling.domain.EstadoTurno;
import com.akine.scheduling.domain.MotivoDeOmision;
import com.akine.scheduling.domain.Turno;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Que turnos de una serie toca una operacion con alcance, y cuales informa como omitidos
 * (AKINE E-3).
 *
 * <p>La previsualizacion y el comando usan esta MISMA funcion: si calcularan distinto, la cantidad
 * que el operador confirmo no seria la que el comando compara, y la confirmacion explicita de DP-04
 * no confirmaria nada.
 *
 * <p>Pura: el reloj y la consulta de atencion entran por parametro.
 */
record SeleccionDeAlcance(List<Turno> afectados, List<Omitido> omitidos) {

	record Omitido(Turno turno, MotivoDeOmision motivo) {
	}

	/**
	 * @param deLaSerie     todos los turnos de la serie, en cualquier estado
	 * @param pivote        el turno desde el que se cuenta; obligatorio salvo en
	 *                      {@link AlcanceDeSerie#TODA_LA_SERIE}
	 * @param tieneAtencion si el turno tiene una Sesion registrada (DP-05)
	 */
	static SeleccionDeAlcance calcular(
			List<Turno> deLaSerie, AlcanceDeSerie alcance, Turno pivote, Instant ahora,
			Predicate<Turno> tieneAtencion) {

		List<Turno> afectados = new ArrayList<>();
		List<Omitido> omitidos = new ArrayList<>();
		for (Turno turno : candidatos(deLaSerie, alcance, pivote)) {
			motivoDeOmision(turno, ahora, tieneAtencion).ifPresentOrElse(
					motivo -> omitidos.add(new Omitido(turno, motivo)),
					() -> afectados.add(turno));
		}
		return new SeleccionDeAlcance(List.copyOf(afectados), List.copyOf(omitidos));
	}

	private static List<Turno> candidatos(List<Turno> deLaSerie, AlcanceDeSerie alcance, Turno pivote) {
		return switch (alcance) {
			case ESTE -> List.of(pivote);
			// Por el horario ACTUAL: despues de mover una ocurrencia sola, "los siguientes" son los
			// que el operador ve despues en el calendario, no los que se generaron despues.
			case ESTE_Y_SIGUIENTES -> deLaSerie.stream()
					.filter(turno -> !turno.getInicio().isBefore(pivote.getInicio()))
					.toList();
			case TODA_LA_SERIE -> deLaSerie;
		};
	}

	/**
	 * DP-04: solo se tocan turnos futuros pendientes. El orden de las preguntas importa poco —un
	 * turno omitido lo esta por cualquiera de ellas— pero la consulta de atencion va ultima porque
	 * es la unica que sale a la base.
	 */
	private static Optional<MotivoDeOmision> motivoDeOmision(
			Turno turno, Instant ahora, Predicate<Turno> tieneAtencion) {

		if (turno.getEstado().esTerminal()) {
			return Optional.of(MotivoDeOmision.ESTADO_TERMINAL);
		}
		if (turno.getEstado() == EstadoTurno.EN_ESPERA) {
			return Optional.of(MotivoDeOmision.EN_ESPERA);
		}
		if (!turno.getInicio().isAfter(ahora)) {
			return Optional.of(MotivoDeOmision.YA_EMPEZO);
		}
		if (tieneAtencion.test(turno)) {
			return Optional.of(MotivoDeOmision.CON_ATENCION);
		}
		return Optional.empty();
	}
}
