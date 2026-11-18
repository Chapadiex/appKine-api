package com.akine.clinical.application;

import com.akine.clinical.domain.EnfermedadDeIngreso;

import java.time.Year;
import java.time.ZoneOffset;

/**
 * Detalle opcional de un antecedente que trae la ficha de ingreso: zona, año y lugar de una
 * cirugia, y el codigo de una enfermedad de la lista.
 */
public record DetalleDeAntecedente(String zona, Short anio, String lugar, EnfermedadDeIngreso codigo) {

	public static final DetalleDeAntecedente NINGUNO = new DetalleDeAntecedente(null, null, null, null);

	public DetalleDeAntecedente {
		// UTC va por delante de la zona de cualquier sede argentina: comparar contra el año UTC
		// nunca rechaza el año en curso de la sede.
		if (anio != null && (anio < 1900 || anio > Year.now(ZoneOffset.UTC).getValue())) {
			throw new IllegalArgumentException("El año de la cirugia tiene que estar entre 1900 y el actual");
		}
	}
}
