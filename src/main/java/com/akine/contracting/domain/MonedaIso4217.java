package com.akine.contracting.domain;

import java.util.Currency;
import java.util.Locale;

/**
 * Normaliza y valida un codigo de moneda contra ISO 4217.
 *
 * <p>El {@code @Size(3)} de los DTO deja pasar cualquier trio de letras —{@code XYZ}— y un convenio
 * no chequeaba ni el largo, asi que {@code PESOS} llegaba a la columna de 3 y terminaba en un error
 * de base. La lista la da {@link Currency}, que es la del JDK: no hay tabla propia que mantener.
 */
final class MonedaIso4217 {

	static final String MENSAJE = "La moneda se declara con su codigo ISO 4217 de 3 letras";

	private MonedaIso4217() {
	}

	/** Mayusculas y sin espacios, o {@link IllegalArgumentException} si no es un codigo ISO 4217. */
	static String normalizar(String moneda) {
		String codigo = moneda.strip().toUpperCase(Locale.ROOT);
		try {
			if (codigo.length() == 3 && Currency.getInstance(codigo) != null) {
				return codigo;
			}
		} catch (IllegalArgumentException noEsIso) {
			// Cae al rechazo de abajo, con el mensaje del dominio.
		}
		throw new IllegalArgumentException(MENSAJE + ": '" + codigo + "' no es uno");
	}
}
