package com.akine.organization.application;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Busqueda de direcciones para ubicar una sede en el mapa (M03, V140).
 *
 * <p>Es una consulta sin efectos sobre el tenant: no escribe nada ni audita nada. La autorizacion
 * —ser miembro de la organizacion— la exige el controller, igual que el listado de sedes. Lo que
 * este servicio agrega al puerto es normalizar lo que se manda: el texto recortado y el pais en
 * mayusculas, que es lo que el adaptador usa como clave de cache.
 */
@Service
public class DireccionesService {

	private final Geocodificador geocodificador;

	public DireccionesService(Geocodificador geocodificador) {
		this.geocodificador = geocodificador;
	}

	/**
	 * @throws IllegalArgumentException              texto vacio (400)
	 * @throws GeocodificadorNoDisponibleException   el proveedor no responde o esta apagado (503)
	 */
	public BusquedaDeDirecciones buscar(String q, String pais) {
		if (q == null || q.isBlank()) {
			throw new IllegalArgumentException("El texto de la direccion a buscar es obligatorio");
		}
		String paisNormalizado = pais == null || pais.isBlank() ? null : pais.strip().toUpperCase(Locale.ROOT);
		return new BusquedaDeDirecciones(
				geocodificador.proveedor(),
				geocodificador.atribucion(),
				geocodificador.buscar(q.strip(), paisNormalizado));
	}

	/** Lo que la pantalla muestra: los candidatos y a quien hay que atribuirselos. */
	public record BusquedaDeDirecciones(
			String proveedor,
			String atribucion,
			List<Geocodificador.DireccionEncontrada> resultados) {
	}
}
