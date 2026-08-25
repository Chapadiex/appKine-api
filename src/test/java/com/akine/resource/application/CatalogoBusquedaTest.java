package com.akine.resource.application;

import com.akine.resource.domain.CatalogoAlcanceFiltro;
import com.akine.resource.domain.CatalogoEstadoFiltro;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Los filtros de la busqueda incremental del catalogo (RF-M06-004).
 *
 * <p>Dos cosas se prueban aca y las dos tienen consecuencias visibles para el usuario: los
 * <b>defaults</b>, que son los que hacen cumplir el criterio de aceptacion de la etapa sin que
 * el cliente tenga que acordarse, y el <b>escape de los comodines</b>, que es lo que evita que
 * un {@code %} tipeado en el buscador devuelva el catalogo entero.
 */
class CatalogoBusquedaTest {

	@Test
	@DisplayName("Sin filtros, un selector busca lo ACTIVO de los DOS catalogos")
	void los_defaults_son_los_de_un_selector() {
		CatalogoBusqueda filtros = new CatalogoBusqueda(null, null, null, null);

		assertThat(filtros.estado())
				.as("es lo que hace que un selector NUNCA ofrezca un concepto dado de baja")
				.isEqualTo(CatalogoEstadoFiltro.ACTIVO);
		assertThat(filtros.alcance())
				.as("el catalogo comun y el propio en la misma lista, que es como se elige una "
						+ "practica en la realidad")
				.isEqualTo(CatalogoAlcanceFiltro.TODOS);
		assertThat(filtros.patron())
				.as("un buscador incremental arranca vacio y tiene que devolver algo")
				.isEqualTo("%");
		assertThat(filtros.activoFiltro()).isEqualTo(1);
		assertThat(filtros.especialidadFiltro()).isEqualTo(-1L);
	}

	@Test
	@DisplayName("Cada estado tiene su centinela, y TODOS es el que no filtra")
	void el_centinela_de_estado() {
		assertThat(busqueda(CatalogoEstadoFiltro.ACTIVO).activoFiltro()).isEqualTo(1);
		assertThat(busqueda(CatalogoEstadoFiltro.INACTIVO).activoFiltro()).isZero();
		assertThat(busqueda(CatalogoEstadoFiltro.TODOS).activoFiltro()).isEqualTo(-1);
	}

	@Test
	@DisplayName("El texto se envuelve en comodines y se recorta")
	void el_patron_envuelve_el_texto() {
		assertThat(new CatalogoBusqueda("  kinesio  ", null, null, null).patron())
				.isEqualTo("%kinesio%");
		assertThat(new CatalogoBusqueda("   ", null, null, null).patron())
				.as("un texto en blanco es lo mismo que no buscar nada")
				.isEqualTo("%");
	}

	@Test
	@DisplayName("Los comodines que tipea el usuario se escapan: un % no devuelve el catalogo "
			+ "entero")
	void los_comodines_del_cliente_se_escapan() {
		assertThat(new CatalogoBusqueda("50%", null, null, null).patron())
				.isEqualTo("%50\\%%");
		assertThat(new CatalogoBusqueda("a_b", null, null, null).patron())
				.as("el guion bajo es comodin de un caracter en LIKE")
				.isEqualTo("%a\\_b%");
		assertThat(new CatalogoBusqueda("c\\d", null, null, null).patron())
				.as("y la barra, que es el caracter de escape, se escapa primero")
				.isEqualTo("%c\\\\d%");
	}

	@Test
	@DisplayName("La especialidad viaja como centinela cuando no se filtra por ella")
	void el_centinela_de_especialidad() {
		assertThat(new CatalogoBusqueda(null, null, null, 7L).especialidadFiltro()).isEqualTo(7L);
	}

	private static CatalogoBusqueda busqueda(CatalogoEstadoFiltro estado) {
		return new CatalogoBusqueda(null, estado, null, null);
	}
}
