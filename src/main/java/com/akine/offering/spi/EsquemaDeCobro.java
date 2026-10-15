package com.akine.offering.spi;

import java.util.Optional;

/**
 * El esquema economico que el centro declaro para una oferta (RN-M27-006), tal como lo cerro
 * {@code V64}. Es lo que otro modulo puede leer para decidir —por ejemplo, si la asistencia a una
 * clase consume credito de un pase (RN-M29-005)— sin conocer la entidad de {@code offering}.
 */
public enum EsquemaDeCobro {

	POR_SESION,
	POR_CLASE,
	POR_PACK,
	POR_ABONO;

	/** Traduce el valor guardado; vacio si la oferta no declaro esquema. */
	public static Optional<EsquemaDeCobro> de(String valor) {
		if (valor == null || valor.isBlank()) {
			return Optional.empty();
		}
		for (EsquemaDeCobro esquema : values()) {
			if (esquema.name().equals(valor.strip())) {
				return Optional.of(esquema);
			}
		}
		return Optional.empty();
	}
}
