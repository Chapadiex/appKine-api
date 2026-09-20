package com.akine.reporting.spi;

/**
 * Un indicador que vale cero <b>por construccion</b> y no por falta de actividad, dicho en voz
 * alta.
 *
 * <h2>Por que existe este tipo</h2>
 *
 * <p>Porque hay un caso real y medible en este repositorio: <b>no existe ninguna obligacion con
 * {@code responsable = FINANCIADOR} y nada la produce</b>. {@code ObligacionDevengador} devenga una
 * sola obligacion a nombre del paciente; el enchufe que V36 reservo y V56 completo nunca se
 * conecto. La consecuencia arrastra toda la cadena:
 *
 * <pre>
 *   sin obligacion de financiador
 *      -&gt; sin prestacion elegible        (AKINE-07.04 ya lo habia declarado)
 *      -&gt; sin presentacion con items
 *      -&gt; prestado = presentado = facturado = pendiente = 0
 * </pre>
 *
 * <p>Un tablero que en produccion muestra ceros sin explicar por que es peor que uno ausente: el
 * operador lee "el mes no tuvo actividad con financiadores" donde en realidad dice "esto todavia no
 * esta cableado", y toma decisiones sobre un vacio que no es un vacio.
 *
 * <p>Viaja un {@code codigo} estable ademas del texto: la pantalla decide como decirlo y puede
 * tratar distinto una advertencia de las que se van a resolver que una permanente.
 *
 * @param seccion seccion que la emite
 * @param codigo  identificador estable, en kebab-case: {@code "sin-devengado-de-financiador"}
 * @param detalle explicacion en prosa, para mostrar tal cual
 */
public record AdvertenciaDeReporte(String seccion, String codigo, String detalle) {

	public AdvertenciaDeReporte {
		if (codigo == null || codigo.isBlank()) {
			throw new IllegalArgumentException("Una advertencia necesita codigo");
		}
	}
}
