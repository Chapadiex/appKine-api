package com.akine.resource.application;

import com.akine.resource.domain.CatalogoAlcanceFiltro;
import com.akine.resource.domain.CatalogoEstadoFiltro;

/**
 * Filtros de la busqueda incremental del catalogo (RF-M06-004).
 *
 * <p>Los defaults son los que un selector necesita y son los que hacen cumplir el criterio de
 * aceptacion de la etapa sin que el cliente tenga que acordarse:
 *
 * <ul>
 *   <li>{@code estado = ACTIVO} — <b>"las nuevas selecciones excluyen inactivos"</b>. Un
 *       formulario que no manda el parametro nunca ofrece un concepto dado de baja;</li>
 *   <li>{@code alcance = TODOS} — el catalogo de plataforma y el propio del centro en la misma
 *       lista, que es como se elige una practica en la realidad;</li>
 *   <li>{@code q = null} — sin texto, todo el catalogo. Un buscador incremental arranca vacio y
 *       tiene que devolver algo.</li>
 * </ul>
 */
public record CatalogoBusqueda(

		/** Texto libre. Se compara contra el nombre Y el codigo, insensible a acentos y caso. */
		String q,

		CatalogoEstadoFiltro estado,

		CatalogoAlcanceFiltro alcance,

		/** Solo aplica a practicas. {@code null} = todas las especialidades. */
		Long especialidadId) {

	public CatalogoBusqueda {
		estado = estado == null ? CatalogoEstadoFiltro.ACTIVO : estado;
		alcance = alcance == null ? CatalogoAlcanceFiltro.TODOS : alcance;
	}

	/**
	 * Patron para el {@code LIKE}, con los comodines puestos por el servidor.
	 *
	 * <p>Los metacaracteres del cliente se escapan: sin eso, un {@code %} tipeado en el buscador
	 * devuelve el catalogo entero y un {@code _} hace de comodin de un caracter. No es un
	 * problema de seguridad —la consulta es parametrizada— pero si de resultados que nadie
	 * entiende.
	 */
	public String patron() {
		if (q == null || q.isBlank()) {
			return "%";
		}
		String escapado = q.strip()
				.replace("\\", "\\\\")
				.replace("%", "\\%")
				.replace("_", "\\_");
		return "%" + escapado + "%";
	}

	/** Centinela del filtro de estado: -1 todos, 1 activos, 0 inactivos. Ver los puertos. */
	public int activoFiltro() {
		return switch (estado) {
			case ACTIVO -> 1;
			case INACTIVO -> 0;
			case TODOS -> -1;
		};
	}

	/** Centinela del filtro de especialidad: -1 significa "todas". */
	public long especialidadFiltro() {
		return especialidadId == null ? -1L : especialidadId;
	}
}
