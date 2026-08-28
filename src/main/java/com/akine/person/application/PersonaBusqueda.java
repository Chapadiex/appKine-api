package com.akine.person.application;

import com.akine.person.domain.ClaveDeBusqueda;

/**
 * Filtros de la busqueda del padron (RF-M07-001).
 *
 * <p>Traduce lo que tipea el operador a lo que el puerto entiende: un patron ya normalizado y ya
 * listo para {@code LIKE}, y dos centinelas enteros. La normalizacion ocurre <b>aca y no en el
 * repositorio</b> por la misma razon por la que las claves se guardan materializadas: lo que se
 * compara contra {@code apellido_clave} tiene que haber pasado por exactamente la misma funcion
 * que produjo esa columna, o la busqueda por "Pérez" no encuentra a "PEREZ".
 *
 * @param estado  filtro por ciclo de vida. {@code null} se toma como {@code ACTIVO}: el padron
 *                que le interesa al mostrador es el vigente, y ofrecer por defecto tambien a las
 *                personas dadas de baja invita a reabrir una ficha que alguien cerro
 * @param perfil  filtro por perfil de paciente. {@code null} es indistinto, que es lo correcto
 *                por defecto: la busqueda del mostrador no sabe todavia si la persona que busca
 *                es paciente
 */
public record PersonaBusqueda(String texto, PersonaEstadoFiltro estado, PerfilFiltro perfil) {

	/** Centinela de "no filtres por estado", mismo criterio que {@code CatalogoBusqueda}. */
	private static final int SIN_FILTRO = -1;

	/**
	 * El patron que se compara contra apellido y nombre.
	 *
	 * <p>{@code %texto%} y no {@code texto%}: un operador que tipea "5678" esta buscando el final
	 * de un documento o de un telefono, que es como se dictan por telefono. El costo es que la
	 * busqueda por texto no usa el indice por prefijo — lo asume a proposito, porque una busqueda
	 * anclada al principio no encuentra lo que el mostrador busca.
	 *
	 * <p>Texto vacio devuelve {@code %}, que contra {@code apellido_clave} —una columna
	 * {@code NOT NULL}— matchea todas las filas. Es el listado completo del padron, paginado.
	 */
	public String patronNombre() {
		return conComodines(ClaveDeBusqueda.deNombre(texto));
	}

	/**
	 * El patron que se compara contra documento y telefono, y <b>es distinto del anterior a
	 * proposito</b>.
	 *
	 * <p>Las dos columnas guardan la clave sin separadores: el documento "12.345.678" esta
	 * almacenado como {@code 12345678} y el telefono "+54 11 5555-0000" como {@code 541155550000}.
	 * Comparar contra el patron de nombre —que conserva puntos, guiones y espacios porque en un
	 * apellido significan algo— no encontraria nunca a la persona que se busca tipeando el
	 * documento como figura en el DNI, que es exactamente como lo tipea el mostrador. Un solo
	 * patron para las cuatro columnas es el bug silencioso que esta division evita.
	 */
	public String patronClave() {
		return conComodines(ClaveDeBusqueda.deDocumento(texto));
	}

	private static String conComodines(String normalizado) {
		return normalizado == null ? "%" : "%" + normalizado + "%";
	}

	/** {@code 1} activas, {@code 0} inactivas, {@code -1} todas. */
	public int activoFiltro() {
		PersonaEstadoFiltro efectivo = estado == null ? PersonaEstadoFiltro.ACTIVO : estado;
		return switch (efectivo) {
			case ACTIVO -> 1;
			case INACTIVO -> 0;
			case TODOS -> SIN_FILTRO;
		};
	}

	/** {@code 1} con perfil vigente, {@code 0} sin perfil, {@code -1} indistinto. */
	public int perfilFiltro() {
		PerfilFiltro efectivo = perfil == null ? PerfilFiltro.TODOS : perfil;
		return switch (efectivo) {
			case CON_PERFIL -> 1;
			case SIN_PERFIL -> 0;
			case TODOS -> SIN_FILTRO;
		};
	}
}
