package com.akine.billing.application;

import java.util.List;

/**
 * El resultado de validar un lote antes de confirmarlo (RF-M21-003).
 *
 * <p><b>Es una lectura: no cambia estado y no bloquea nada por si misma.</b> Lo que bloquea es
 * {@code confirmar}, que la vuelve a correr del lado del servidor. Tenerla aparte le permite a la
 * pantalla mostrar los problemas <b>mientras se arma</b> el lote, en vez de descubrirlos todos
 * juntos al apretar el boton.
 *
 * @param hallazgos vacio cuando el lote esta listo. <b>La lista entera y no el primero</b>: el
 *                  administrativo tiene que poder arreglar todo de una vez
 */
public record ValidacionDePresentacion(long presentacionId, boolean confirmable, List<Reparo> hallazgos) {

	public ValidacionDePresentacion {
		hallazgos = List.copyOf(hallazgos);
	}

	/** Un problema concreto sobre una prestacion concreta. */
	public record Reparo(long itemId, long obligacionId, HallazgoDeValidacion hallazgo) {
	}
}
