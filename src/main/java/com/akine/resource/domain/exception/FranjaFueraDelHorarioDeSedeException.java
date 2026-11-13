package com.akine.resource.domain.exception;

import com.akine.resource.domain.IntervaloLocal;

import java.time.LocalTime;
import java.util.List;

/**
 * Una franja de disponibilidad del profesional se sale del horario general de la sede (409).
 *
 * <p>Regla del 08/11/2026: el horario de un profesional en una sede queda DENTRO del horario de
 * esa sede —igual o menos tiempo, nunca mas—. Hasta entonces se guardaba y la disponibilidad
 * efectiva la recortaba al leer ({@code recortadoPor = HORARIO_SEDE}); ese recorte sigue como red
 * de seguridad para lo cargado antes.
 *
 * <p>Lleva TODAS las franjas que no entran, no solo la primera: un lote de lunes a sabado contra
 * una sede que no abre los sabados tiene que poder senalar el sabado sin otro ida y vuelta. Cada
 * una con su indice en el pedido y el horario de la sede ese dia (vacio si ese dia no abre).
 *
 * <p>409 y no 400, como {@link BloqueSolapadoException}: lo que rechaza es el ESTADO —el horario
 * vigente de la sede—, no la forma del pedido. El mismo pedido procede si la sede amplia su horario.
 */
public class FranjaFueraDelHorarioDeSedeException extends RuntimeException {

	/** Una franja pedida que no entra, con el horario de la sede para su dia. */
	public record FranjaFuera(
			int indice, int diaSemana, LocalTime horaDesde, LocalTime horaHasta, List<IntervaloLocal> horarioDeSede) {

		public FranjaFuera {
			horarioDeSede = List.copyOf(horarioDeSede);
		}
	}

	private static final String[] DIAS = {"lunes", "martes", "miercoles", "jueves", "viernes", "sabados", "domingos"};

	private final List<FranjaFuera> franjas;

	public FranjaFueraDelHorarioDeSedeException(List<FranjaFuera> franjas) {
		super(detalle(franjas.getFirst()));
		this.franjas = List.copyOf(franjas);
	}

	public List<FranjaFuera> getFranjas() {
		return franjas;
	}

	/** El {@code detail}: la primera franja que no entra y el horario de la sede ese dia. */
	private static String detalle(FranjaFuera franja) {
		String dia = DIAS[Math.floorMod(franja.diaSemana() - 1, 7)];
		if (franja.horarioDeSede().isEmpty()) {
			return "La sede no abre los " + dia + ": el horario del profesional tiene que quedar dentro del "
					+ "horario de la sede.";
		}
		return "Los " + dia + " la sede abre " + describir(franja.horarioDeSede())
				+ ": el horario del profesional tiene que quedar dentro del horario de la sede.";
	}

	private static String describir(List<IntervaloLocal> horario) {
		StringBuilder texto = new StringBuilder();
		for (int i = 0; i < horario.size(); i++) {
			if (i > 0) {
				texto.append(i == horario.size() - 1 ? " y " : ", ");
			}
			texto.append("de ").append(hora(horario.get(i).desde()))
					.append(" a ").append(hora(horario.get(i).hasta()));
		}
		return texto.toString();
	}

	private static String hora(LocalTime hora) {
		return IntervaloLocal.FIN_DE_DIA.equals(hora) ? "24:00"
				: hora.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
	}
}
