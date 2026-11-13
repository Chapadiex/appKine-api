package com.akine.organization.domain;

import java.util.Locale;

/**
 * Colores de las zonas de la interfaz que un centro puede fijar ademas del principal y el acento,
 * para un tema. Cada uno es opcional: {@code null} significa "derivalo del color principal".
 *
 * @param barraLateral fondo de la navegacion
 * @param cabecera fondo de la barra superior
 * @param fondo fondo de la pagina, detras de las tarjetas
 * @param tarjeta fondo de las tarjetas, paneles y campos
 * @param pie fondo del pie de pagina
 * @param botonSecundario contorno y texto de los botones de contorno
 */
public record ColoresDeInterfaz(
		String barraLateral, String cabecera, String fondo, String tarjeta, String pie,
		String botonSecundario) {

	public static final ColoresDeInterfaz NINGUNO = new ColoresDeInterfaz(null, null, null, null, null, null);

	/** Vacio es {@code null} y el color queda en minusculas, como se guarda. */
	public static ColoresDeInterfaz normalizar(ColoresDeInterfaz colores) {
		if (colores == null) {
			return NINGUNO;
		}
		return new ColoresDeInterfaz(
				color(colores.barraLateral()), color(colores.cabecera()),
				color(colores.fondo()), color(colores.tarjeta()), color(colores.pie()),
				color(colores.botonSecundario()));
	}

	private static String color(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip().toLowerCase(Locale.ROOT);
	}
}
