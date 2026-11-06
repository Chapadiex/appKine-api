package com.akine.organization.domain;

/**
 * De donde salieron las coordenadas de una sede.
 *
 * <p>Importa para la pantalla y para el soporte: una ubicacion {@code GEOCODIFICADA} es la que
 * devolvio el geocodificador para la direccion escrita, y vale lo que vale ese servicio; una
 * {@code MANUAL} la fijo una persona moviendo el marcador, y es la que manda si las dos difieren.
 */
public enum UbicacionOrigen {
	GEOCODIFICADA,
	MANUAL
}
