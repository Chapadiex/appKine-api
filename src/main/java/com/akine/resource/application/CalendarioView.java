package com.akine.resource.application;

import com.akine.resource.domain.CalendarioSede;

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
 */
public record CalendarioView(
		long consultorioId,
		String pais,
		boolean cierraPorFeriado,
		long version,
		boolean existePersistida,
		List<FeriadoView> feriados) {

	public CalendarioView {
		feriados = feriados == null ? List.of() : List.copyOf(feriados);
	}

	/** Vista de una fila que existe en la base. */
	public static CalendarioView de(CalendarioSede calendario, List<FeriadoView> feriados) {
		return new CalendarioView(
				calendario.getConsultorioId(),
				calendario.getPais(),
				calendario.isCierraPorFeriado(),
				calendario.getVersion(),
				true,
				feriados);
	}

	/**
	 * Vista de los valores por defecto, para la sede que todavia no tiene fila.
	 *
	 * <p>La lectura NO crea la fila: un GET que escribe es una trampa —convierte una consulta en
	 * una mutacion que nadie pidio y la haria fallar en una transaccion de solo lectura—. La fila
	 * aparece en la primera edicion de politica o en el primer write de disponibilidad de la
	 * sede, que son los dos lugares que ya tienen transaccion de escritura.
	 */
	public static CalendarioView porDefecto(long consultorioId, List<FeriadoView> feriados) {
		return new CalendarioView(
				consultorioId,
				CalendarioSede.PAIS_POR_DEFECTO,
				CalendarioSede.CIERRA_POR_FERIADO_POR_DEFECTO,
				0L,
				false,
				feriados);
	}
}
