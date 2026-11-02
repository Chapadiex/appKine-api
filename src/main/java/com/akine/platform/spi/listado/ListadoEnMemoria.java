package com.akine.platform.spi.listado;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Busqueda y orden de los listados que ya se paginan en memoria (DP-45, punto 1): catalogos,
 * espacios, sedes. Son listas acotadas por naturaleza —los espacios de una sede, las sedes de un
 * tenant— y su paginado ya se hacia sobre la lista completa, asi que filtrar y ordenar antes de
 * cortar la pagina no cambia el costo.
 */
public final class ListadoEnMemoria {

	private ListadoEnMemoria() {
	}

	/**
	 * Filtra y, si se pidio, ordena. Sin orden pedido conserva el orden en el que llegaron las
	 * filas, que es el de siempre.
	 *
	 * @param comparadores el comparador ASCENDENTE de cada campo de la lista blanca
	 * @param desempate un comparador unico —el id—, para que el paginado sea estable
	 */
	public static <T> List<T> filtrarYOrdenar(
			List<T> filas,
			Predicate<T> coincide,
			Optional<OrdenDeListado> orden,
			Map<String, Comparator<T>> comparadores,
			Comparator<T> desempate) {

		var filtradas = filas.stream().filter(coincide);
		if (orden.isEmpty()) {
			return filtradas.toList();
		}
		Comparator<T> porCampo = comparadores.get(orden.get().campo());
		if (porCampo == null) {
			throw new IllegalStateException("Campo de orden sin comparador: " + orden.get().campo());
		}
		return filtradas.sorted(orden.get().aplicar(porCampo, desempate)).toList();
	}
}
