package com.akine.reporting.domain.exception;

/**
 * El periodo pedido no sirve para reportar: invertido, o mas ancho que la ventana maxima.
 *
 * <p>Lleva {@code maximoDias} para que el Problem Detail lo publique: sin ese numero la pantalla
 * solo puede decir "el rango es invalido" y dejar al usuario probando hasta acertar.
 */
public class RangoDeReporteInvalidoException extends RuntimeException {

	private final int maximoDias;

	public RangoDeReporteInvalidoException(String mensaje, int maximoDias) {
		super(mensaje);
		this.maximoDias = maximoDias;
	}

	public int getMaximoDias() {
		return maximoDias;
	}
}
