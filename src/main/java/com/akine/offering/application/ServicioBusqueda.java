package com.akine.offering.application;

/**
 * Filtros de la busqueda del catalogo global de Servicios (contrato: {@code GET /servicios}).
 *
 * <p><b>No lleva ningun filtro de alcance ni de tenant, y no es un olvido:</b> {@code servicio}
 * es una sola poblacion global sin {@code organization_id} (ADR-0023). Donde
 * {@code CatalogoBusqueda} necesita un {@code alcance} para separar el catalogo de plataforma del
 * propio del centro, aca no hay dos catalogos que separar: lo propio de cada centro es la Oferta,
 * y esa se lista por otra ruta.
 */
public record ServicioBusqueda(

		/** Texto libre. Se compara contra el nombre Y el codigo, insensible a acentos y caso. */
		String q,

		ServicioEstadoFiltro estado) {

	public ServicioBusqueda {
		estado = estado == null ? ServicioEstadoFiltro.ACTIVO : estado;
	}

	/**
	 * Patron para el {@code LIKE}, con los comodines puestos por el servidor.
	 *
	 * <p>Los metacaracteres del cliente se escapan: sin eso, un {@code %} tipeado en el buscador
	 * devuelve el catalogo entero y un {@code _} hace de comodin de un caracter. No es un
	 * problema de seguridad —la consulta es parametrizada— pero si de resultados que nadie
	 * entiende. Mismo escape que {@code CatalogoBusqueda.patron()}.
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

	/**
	 * Centinela del filtro de estado: -1 todos, 1 activos, 0 inactivos.
	 *
	 * <p>Es un {@code int} y no un {@code Boolean} nulable porque la consulta de
	 * {@code ServicioRepository} es nativa: un parametro nulo en una nativa dispara el
	 * "could not determine type" de Hibernate. Mismo centinela y mismo motivo que
	 * {@code CatalogoRepositoryPorts}.
	 */
	public int activoFiltro() {
		return switch (estado) {
			case ACTIVO -> 1;
			case INACTIVO -> 0;
			case TODOS -> -1;
		};
	}
}
