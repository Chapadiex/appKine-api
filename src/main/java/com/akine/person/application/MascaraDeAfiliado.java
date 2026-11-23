package com.akine.person.application;

/**
 * Enmascara el numero de afiliado: los ultimos cuatro caracteres a la vista y el resto tapado. Un
 * numero de cuatro o menos se tapa entero: mostrarlo "enmascarado" seria mostrarlo completo.
 */
public final class MascaraDeAfiliado {

	private static final int DIGITOS_VISIBLES = 4;
	private static final String MASCARA = "···";

	private MascaraDeAfiliado() {
	}

	public static String enmascarar(String numeroAfiliado) {
		if (numeroAfiliado == null || numeroAfiliado.isBlank()) {
			return null;
		}
		String limpio = numeroAfiliado.strip();
		if (limpio.length() <= DIGITOS_VISIBLES) {
			return MASCARA;
		}
		return MASCARA + limpio.substring(limpio.length() - DIGITOS_VISIBLES);
	}
}
