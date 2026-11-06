package com.akine.organization.infrastructure.geo;

import com.akine.organization.application.Geocodificador;
import com.akine.organization.application.GeocodificadorNoDisponibleException;

import java.util.List;

/**
 * El geocodificador cuando {@code akine.geo.proveedor = NINGUNO}: toda busqueda responde 503 y la
 * sede se ubica a mano. Existe para que el bean siempre este y el controller no tenga un camino
 * "sin proveedor" propio.
 */
public final class GeocodificadorApagado implements Geocodificador {

	@Override
	public List<DireccionEncontrada> buscar(String q, String pais) {
		throw new GeocodificadorNoDisponibleException(
				"La geocodificacion esta apagada en este despliegue (akine.geo.proveedor = NINGUNO)");
	}

	@Override
	public String proveedor() {
		return "NINGUNO";
	}

	@Override
	public String atribucion() {
		return "";
	}
}
