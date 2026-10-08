package com.akine.resource.application;

import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.FranjaHorarioGeneral;

import java.util.List;

/**
 * La politica de calendario de una sede y, cuando se pide con una ventana, los feriados que caen
 * dentro de ella.
 *
 * <h2>Por que {@code feriados} viene vacia en la edicion</h2>
 *
 * <p>La lectura del calendario responde dos cosas que la pantalla necesita juntas: si esta sede
 * cierra los feriados, y cuales son los feriados del periodo que se esta mirando. La EDICION de
 * la politica no lleva ventana —un PUT no tiene {@code desde} ni {@code hasta}—, asi que devuelve
 * la lista vacia. Vacia significa "no se pregunto", nunca "no hay feriados": quien quiera la
 * lista despues de editar vuelve a leer con su ventana.
 *
 * <h2>{@code version} viaja aunque la edicion no la exija</h2>
 *
 * <p>La escritura de politica se serializa con el {@code FOR UPDATE} sobre esta misma fila y no
 * pide {@code expectedVersion}: la fila se crea a demanda, asi que un cliente que nunca la vio no
 * tiene ninguna version que mandar y exigirsela le impediria su primera edicion. La version viaja
 * igual porque es lo que le permite a la pantalla detectar que alguien mas la cambio.
 *
 * @param existePersistida {@code false} cuando la sede todavia no tiene fila propia y lo que se
 *                         devuelve son los valores por defecto de V23. Es informacion util: dice
 *                         que nadie edito nunca la politica de esa sede, no que no tenga una
 * @param horarioGeneral   franjas VIGENTES del horario general de la sede (RF-M03-002), ordenadas
 *                         por dia y hora. Vacia significa que la sede no declaro horario general,
 *                         no que este cerrada. Desde A-8b (DP-19) es un LIMITE de la agenda:
 *                         la disponibilidad de cada profesional se recorta a este horario, y
 *                         vacia no limita nada
 * @param impactoDelHorario turnos pendientes que el reemplazo del horario dejo fuera de la
 *                         disponibilidad (A-8b). Cero en la lectura y en un PUT que no cambio
 *                         el horario
 */
public record CalendarioView(
		long consultorioId,
		String pais,
		boolean cierraPorFeriado,
		long version,
		boolean existePersistida,
		List<FeriadoView> feriados,
		List<FranjaHorarioGeneral.Franja> horarioGeneral,
		ImpactoDeDisponibilidad impactoDelHorario) {

	public CalendarioView {
		feriados = feriados == null ? List.of() : List.copyOf(feriados);
		horarioGeneral = horarioGeneral == null ? List.of() : List.copyOf(horarioGeneral);
		impactoDelHorario = impactoDelHorario == null
				? ImpactoDeDisponibilidad.ninguno(null)
				: impactoDelHorario;
	}

	/** Sin impacto informado: la lectura, y todo lo anterior a A-8b. */
	public CalendarioView(
			long consultorioId,
			String pais,
			boolean cierraPorFeriado,
			long version,
			boolean existePersistida,
			List<FeriadoView> feriados,
			List<FranjaHorarioGeneral.Franja> horarioGeneral) {
		this(consultorioId, pais, cierraPorFeriado, version, existePersistida, feriados,
				horarioGeneral, null);
	}

	/**
	 * La misma vista con los turnos pendientes que el reemplazo del horario dejo fuera (A-8b). Solo
	 * la respuesta del {@code PUT} que cambio el horario lo lleva distinto de cero.
	 */
	public CalendarioView conImpacto(ImpactoDeDisponibilidad impacto) {
		return new CalendarioView(consultorioId, pais, cierraPorFeriado, version, existePersistida,
				feriados, horarioGeneral, impacto);
	}

	/** Sin horario general: la forma de la vista anterior a A-8. */
	public CalendarioView(
			long consultorioId,
			String pais,
			boolean cierraPorFeriado,
			long version,
			boolean existePersistida,
			List<FeriadoView> feriados) {
		this(consultorioId, pais, cierraPorFeriado, version, existePersistida, feriados, List.of());
	}

	/** Vista de una fila que existe en la base. */
	public static CalendarioView de(
			CalendarioSede calendario,
			List<FeriadoView> feriados,
			List<FranjaHorarioGeneral.Franja> horarioGeneral) {
		return new CalendarioView(
				calendario.getConsultorioId(),
				calendario.getPais(),
				calendario.isCierraPorFeriado(),
				calendario.getVersion(),
				true,
				feriados,
				horarioGeneral);
	}

	/**
	 * Vista de los valores por defecto, para la sede que todavia no tiene fila.
	 *
	 * <p>La lectura NO crea la fila: un GET que escribe es una trampa —convierte una consulta en
	 * una mutacion que nadie pidio y la haria fallar en una transaccion de solo lectura—. La fila
	 * aparece en la primera edicion de politica o en el primer write de disponibilidad de la
	 * sede, que son los dos lugares que ya tienen transaccion de escritura.
	 *
	 * <p>El horario general es independiente de esa fila: una sede creada con horario y nunca
	 * editada no tiene fila de politica y si tiene horario.
	 */
	public static CalendarioView porDefecto(
			long consultorioId,
			List<FeriadoView> feriados,
			List<FranjaHorarioGeneral.Franja> horarioGeneral) {
		return new CalendarioView(
				consultorioId,
				CalendarioSede.PAIS_POR_DEFECTO,
				CalendarioSede.CIERRA_POR_FERIADO_POR_DEFECTO,
				0L,
				false,
				feriados,
				horarioGeneral);
	}
}
