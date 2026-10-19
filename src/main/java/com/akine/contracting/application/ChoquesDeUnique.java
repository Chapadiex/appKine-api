package com.akine.contracting.application;

import org.springframework.dao.DataIntegrityViolationException;

import java.util.Locale;

/**
 * Cual unique choco, leido del mensaje de MySQL, que nombra el indice violado. Es la unica senal
 * disponible: la excepcion de Spring no lleva la constraint en ningun campo estructurado. Mismo
 * criterio que {@code FinanciadorService.indiceContiene}.
 */
final class ChoquesDeUnique {

	private ChoquesDeUnique() {
		// Utilidad.
	}

	static boolean contiene(DataIntegrityViolationException choque, String fragmento) {
		String mensaje = choque.getMostSpecificCause().getMessage();
		return mensaje != null && mensaje.toLowerCase(Locale.ROOT).contains(fragmento);
	}
}
