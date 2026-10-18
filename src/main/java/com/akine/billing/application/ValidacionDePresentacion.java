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
 * @param hallazgos lo que <b>impide</b> confirmar; vacio cuando el lote esta listo. <b>La lista
 *                  entera y no el primero</b>: el administrativo tiene que poder arreglar todo de
 *                  una vez
 * @param avisos    lo que conviene regularizar y <b>no</b> impide confirmar (AKINE-07.04b): hoy,
 *                  la credencial. Lista aparte y no una severidad por reparo, para que un cliente
 *                  que lee {@code hallazgos} como "lo que bloquea" siga teniendo razon
 */
public record ValidacionDePresentacion(
		long presentacionId, boolean confirmable, List<Reparo> hallazgos, List<Reparo> avisos) {

	public ValidacionDePresentacion {
		hallazgos = List.copyOf(hallazgos);
		avisos = List.copyOf(avisos);
	}

	/** Un problema concreto sobre una prestacion concreta. */
	public record Reparo(long itemId, long obligacionId, HallazgoDeValidacion hallazgo) {
	}
}
