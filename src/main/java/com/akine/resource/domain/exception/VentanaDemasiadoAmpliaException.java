package com.akine.resource.domain.exception;

import java.time.LocalDate;

/**
 * La ventana de fechas consultada supera el tope admitido (400).
 *
 * <h2>Por que existe un tope</h2>
 *
 * <p>La disponibilidad efectiva NO esta materializada (diseno §2.5): se calcula dia por dia
 * sobre los bloques, las excepciones y los feriados de la ventana. Sin tope, un
 * {@code desde=1970} sobre un tenant grande no es una consulta sino un scan completo, un dia
 * por iteracion, y un problema de disponibilidad del servicio. Es el mismo razonamiento con el
 * que la matriz de permisos acota los listados con rango.
 *
 * <p>400 y no 409: lo que rechaza la operacion es la FORMA del pedido —un parametro fuera de
 * rango—, no el estado del sistema. El mismo pedido con una ventana mas chica procede siempre.
 *
 * <p><b>La ventana invertida NO sale por aca</b>, sale por {@code IllegalArgumentException}:
 * tambien es 400, pero es un pedido incoherente y no uno demasiado grande, y el detalle que la
 * pantalla tiene que mostrar es otro. Ver {@code VentanaConsultable}.
 *
 * <p><b>Nota para la tarea 10:</b> el advice del modulo tiene que mapearla a <b>400</b> con un
 * {@code type} propio, y el detalle deberia incluir {@link #getMaximoDias()} para que la
 * pantalla pueda recortar la ventana sola en vez de mostrarle el error al usuario.
 */
public class VentanaDemasiadoAmpliaException extends RuntimeException {

	private final LocalDate desde;
	private final LocalDate hasta;
	private final int maximoDias;

	public VentanaDemasiadoAmpliaException(LocalDate desde, LocalDate hasta, int maximoDias) {
		super("La ventana " + desde + " -> " + hasta + " supera el maximo de " + maximoDias
				+ " dias consultables");
		this.desde = desde;
		this.hasta = hasta;
		this.maximoDias = maximoDias;
	}

	public LocalDate getDesde() {
		return desde;
	}

	public LocalDate getHasta() {
		return hasta;
	}

	public int getMaximoDias() {
		return maximoDias;
	}
}
