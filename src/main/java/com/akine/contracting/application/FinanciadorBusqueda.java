package com.akine.contracting.application;

import com.akine.contracting.domain.TipoFinanciador;

/**
 * Filtros del buscador de financiadores (RF-M15-006, {@code GET /financiadores}).
 *
 * <p><b>No lleva {@code organizationId}</b> y no es un olvido: la organizacion sale del contexto
 * que el filtro de tenant ya revalido, nunca de un parametro del cliente. Aceptarla por parametro
 * permitiria que alguien escribiera otro numero y leyera el catalogo de otro centro.
 */
public record FinanciadorBusqueda(

		/** Texto libre. Se compara contra el nombre, el codigo y el CUIT. */
		String q,

		EstadoFiltro estado,

		/** {@code null} = todos los tipos. */
		TipoFinanciador tipo) {

	public FinanciadorBusqueda {
		estado = estado == null ? EstadoFiltro.ACTIVO : estado;
	}

	/**
	 * Patron para el {@code LIKE}, con los comodines puestos por el servidor.
	 *
	 * <p>Los metacaracteres del cliente se escapan: sin eso, un {@code %} tipeado en el buscador
	 * devuelve el catalogo entero y un {@code _} hace de comodin de un caracter. No es un problema
	 * de seguridad —la consulta es parametrizada— pero si de resultados que nadie entiende. Mismo
	 * escape que {@code ServicioBusqueda.patron()}.
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
	 * <p>Es un {@code int} y no un {@code Boolean} nulable porque la consulta del repositorio es
	 * nativa: un parametro nulo en una nativa dispara el "could not determine type" de Hibernate.
	 * Mismo centinela y mismo motivo que {@code ServicioBusqueda}.
	 */
	public int activoFiltro() {
		return switch (estado) {
			case ACTIVO -> 1;
			case INACTIVO -> 0;
			case TODOS -> -1;
		};
	}

	/** El tipo como texto para la nativa, o {@code null} para no filtrar. */
	public String tipoFiltro() {
		return tipo == null ? null : tipo.name();
	}
}
