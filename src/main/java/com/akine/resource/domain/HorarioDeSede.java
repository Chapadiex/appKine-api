package com.akine.resource.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * El horario general de una sede como LIMITE de la disponibilidad efectiva (A-8b, DP-19).
 *
 * <h2>La regla</h2>
 *
 * <p>Hasta A-8 el horario general era informativo (RN-M03-004 leido como "no se mezcla con la
 * agenda"). DP-19 lo convierte en techo: ningun turno se ofrece ni se reserva fuera del horario de
 * la sede, aunque el profesional tenga disponibilidad cargada. <b>Una sede sin horario cargado no
 * limita nada</b>: es lo que dejan las sedes anteriores a A-8 y las que se dieron de alta sin
 * declararlo, y para ellas la agenda tiene que seguir igual que antes.
 *
 * <p>La disponibilidad efectiva queda <b>intersectada</b>, no reemplazada: el horario de la sede
 * nunca abre nada que el profesional no tenga —eso sigue siendo RN-M03-004, el horario general no
 * reemplaza la disponibilidad individual—, solo recorta.
 *
 * <h2>Por que las franjas contiguas se unen antes de intersectar</h2>
 *
 * <p>Una sede que declara 09-13 y 13-17 esta abierta de 09 a 17 sin interrupcion. Intersectar
 * contra las dos franjas por separado partiria el bloque 08-18 del profesional en 09-13 y 13-17, y
 * como el motor de slots corta cada franja por separado, el turno de 12:30 a 13:30 desapareceria
 * sin que nadie lo haya cerrado. Por eso se unen las que se tocan.
 *
 * <h2>Que es puro</h2>
 *
 * <p>No lee nada ni conoce husos: recibe las franjas ya leidas y trabaja en hora local, igual que
 * {@link DisponibilidadEfectivaCalculator}, que es quien lo aplica como su ultima etapa.
 */
public final class HorarioDeSede {

	private static final HorarioDeSede SIN_LIMITE = new HorarioDeSede(Map.of());

	/** Franjas unidas por dia ISO (1 = lunes), ordenadas. Vacio = la sede no declaro horario. */
	private final Map<Integer, List<IntervaloLocal>> porDia;

	private HorarioDeSede(Map<Integer, List<IntervaloLocal>> porDia) {
		this.porDia = porDia;
	}

	/** La sede no declaro horario general: no se limita nada. */
	public static HorarioDeSede sinLimite() {
		return SIN_LIMITE;
	}

	/** Construye el limite a partir de las franjas vigentes. Lista nula o vacia: sin limite. */
	public static HorarioDeSede de(List<FranjaHorarioGeneral.Franja> franjas) {
		if (franjas == null || franjas.isEmpty()) {
			return SIN_LIMITE;
		}
		Map<Integer, List<IntervaloLocal>> crudo = new TreeMap<>();
		for (FranjaHorarioGeneral.Franja franja : franjas) {
			crudo.computeIfAbsent(franja.diaSemana(), dia -> new ArrayList<>())
					.add(new IntervaloLocal(franja.horaDesde(), franja.horaHasta()));
		}
		Map<Integer, List<IntervaloLocal>> unido = new TreeMap<>();
		crudo.forEach((dia, intervalos) -> unido.put(dia, unir(intervalos)));
		return new HorarioDeSede(unido);
	}

	/** {@code true} si la sede declaro horario general y por lo tanto recorta. */
	public boolean limita() {
		return !porDia.isEmpty();
	}

	/**
	 * Recorta un dia ya calculado al horario de la sede.
	 *
	 * <ul>
	 *   <li>Sin horario cargado, o un dia que ya estaba vacio: el dia sale igual. El vacio conserva
	 *       su razon —un feriado sigue siendo feriado aunque la sede tampoco abra ese dia—.</li>
	 *   <li>Franja que cae entera dentro del horario: intacta.</li>
	 *   <li>Franja que lo excede: queda la parte comun, con {@code recortadoPor = HORARIO_SEDE}.</li>
	 *   <li>Nada en comun —incluido el dia de la semana en el que la sede no declaro ninguna
	 *       franja—: dia vacio con {@link OrigenFranja#HORARIO_SEDE} como razon. Es lo que le dice al
	 *       operador que lo que tiene que corregir es el horario de la sede, no el del profesional.</li>
	 * </ul>
	 */
	public DiaCalculado limitar(LocalDate fecha, DiaCalculado dia) {
		if (!limita() || dia.estaVacio()) {
			return dia;
		}
		List<IntervaloLocal> abierto = porDia.getOrDefault(fecha.getDayOfWeek().getValue(), List.of());
		List<FranjaEfectiva> dentro = new ArrayList<>();
		for (FranjaEfectiva franja : dia.franjas()) {
			for (IntervaloLocal deLaSede : abierto) {
				franja.intervalo().interseccion(deLaSede).ifPresent(comun -> dentro.add(
						new FranjaEfectiva(
								comun,
								franja.origen(),
								comun.equals(franja.intervalo())
										? franja.recortadoPor()
										: OrigenFranja.HORARIO_SEDE,
								franja.reglaId())));
			}
		}
		if (dentro.isEmpty()) {
			return DiaCalculado.vacio(OrigenFranja.HORARIO_SEDE, null);
		}
		dentro.sort(Comparator.<FranjaEfectiva, java.time.LocalTime>comparing(
						franja -> franja.intervalo().desde())
				.thenComparing(franja -> franja.intervalo().hasta()));
		return DiaCalculado.con(dentro);
	}

	/**
	 * {@code true} si el intervalo local de esa fecha cae ENTERO dentro del horario de la sede. Sin
	 * horario cargado, siempre. Es el control de la reserva para las ofertas que no tienen
	 * profesional y por lo tanto no pasan por la disponibilidad efectiva.
	 */
	public boolean cubre(LocalDate fecha, IntervaloLocal intervalo) {
		if (!limita()) {
			return true;
		}
		return porDia.getOrDefault(fecha.getDayOfWeek().getValue(), List.of()).stream()
				.anyMatch(deLaSede -> deLaSede.contiene(intervalo));
	}

	private static List<IntervaloLocal> unir(List<IntervaloLocal> intervalos) {
		List<IntervaloLocal> ordenados = new ArrayList<>(intervalos);
		ordenados.sort(Comparator.comparing(IntervaloLocal::desde)
				.thenComparing(IntervaloLocal::hasta));
		List<IntervaloLocal> unidos = new ArrayList<>();
		for (IntervaloLocal actual : ordenados) {
			if (!unidos.isEmpty() && unidos.get(unidos.size() - 1).esContiguoCon(actual)) {
				IntervaloLocal ultimo = unidos.remove(unidos.size() - 1);
				unidos.add(new IntervaloLocal(
						ultimo.desde(),
						ultimo.hasta().isAfter(actual.hasta()) ? ultimo.hasta() : actual.hasta()));
			} else {
				unidos.add(actual);
			}
		}
		return List.copyOf(unidos);
	}
}
