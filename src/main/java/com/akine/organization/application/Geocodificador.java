package com.akine.organization.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * Puerto de geocodificacion: de un texto de direccion a candidatos con coordenadas.
 *
 * <p>Lo implementa {@code infrastructure.geo} (hoy Nominatim de OpenStreetMap, o un adaptador
 * apagado). La aplicacion no sabe que proveedor hay detras: solo que puede no estar
 * ({@link GeocodificadorNoDisponibleException}) y que cada respuesta exige mostrar una
 * atribucion.
 *
 * <p><b>No es autocompletado.</b> Se consulta cuando la persona termina de escribir y pide
 * buscar, nunca tecla a tecla: la politica de uso de Nominatim lo prohibe y ningun proveedor
 * gratuito lo aguanta.
 */
public interface Geocodificador {

	/**
	 * @param q    direccion escrita, ya recortada
	 * @param pais ISO 3166-1 alpha-2 para acotar la busqueda, o {@code null}
	 * @return hasta cinco candidatos, los mejores primero; vacio si no hay ninguno
	 * @throws GeocodificadorNoDisponibleException si el proveedor no responde o esta apagado
	 */
	List<DireccionEncontrada> buscar(String q, String pais);

	/** Nombre del proveedor, para la respuesta y el log. */
	String proveedor();

	/** Texto de atribucion que la pantalla tiene que mostrar junto a los resultados. */
	String atribucion();

	/**
	 * Un candidato. Las partes de la direccion pueden faltar; la etiqueta y las coordenadas no.
	 *
	 * @param pais ISO 3166-1 alpha-2 en mayusculas, o {@code null}
	 */
	record DireccionEncontrada(
			String etiqueta,
			BigDecimal latitud,
			BigDecimal longitud,
			String calle,
			String numero,
			String ciudad,
			String provincia,
			String codigoPostal,
			String pais) {
	}
}
