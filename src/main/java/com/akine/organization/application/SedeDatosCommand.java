package com.akine.organization.application;

import com.akine.organization.domain.UbicacionOrigen;

import java.math.BigDecimal;

/**
 * Direccion fisica, ubicacion y opciones de una sede (V140), tal como viajan en el alta y en la
 * edicion.
 *
 * <p>Semantica de PATCH en los dos casos: cada texto {@code null} deja lo que estaba y la cadena
 * vacia lo borra; cada booleano {@code null} deja lo que estaba. En el alta "lo que estaba" son
 * los defaults de la entity (agenda habilitada, direccion publica, datos fiscales heredados).
 *
 * @param ubicacion {@code null} deja las coordenadas como estan. Con las dos coordenadas las
 *                  fija; con las dos en {@code null} las borra. Una sola es 400
 */
public record SedeDatosCommand(
		String alias,
		String pais,
		String provincia,
		String ciudad,
		String calle,
		String numero,
		String codigoPostal,
		String piso,
		String referencias,
		UbicacionCommand ubicacion,
		Boolean agendaHabilitada,
		Boolean direccionPublica,
		Boolean datosFiscalesPropios) {

	/** Ningun dato de V140: la forma de un cliente anterior. */
	public static SedeDatosCommand ninguno() {
		return new SedeDatosCommand(
				null, null, null, null, null, null, null, null, null, null, null, null, null);
	}

	/** {@code true} si no trae nada: ni direccion, ni ubicacion, ni opciones. */
	public boolean vacio() {
		return alias == null && pais == null && provincia == null && ciudad == null && calle == null
				&& numero == null && codigoPostal == null && piso == null && referencias == null
				&& ubicacion == null && agendaHabilitada == null && direccionPublica == null
				&& datosFiscalesPropios == null;
	}

	/**
	 * Coordenadas pedidas. Las dos en {@code null} es "borrar la ubicacion".
	 *
	 * @param origen quien la puso; {@code null} se toma como MANUAL
	 */
	public record UbicacionCommand(BigDecimal latitud, BigDecimal longitud, UbicacionOrigen origen) {

		public boolean borrar() {
			return latitud == null && longitud == null;
		}
	}
}
