package com.akine.person.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B-1, lado aplicacion: el patron con el que se busca el numero de afiliado. La consulta lo compara
 * contra el numero guardado sin separadores y en mayusculas, asi que el patron tiene que llegar en
 * esa misma forma: comodines a ambos lados (match contiene) y solo A-Z0-9.
 */
@DisplayName("Busqueda por numero de afiliado: patron (B-1)")
class PersonaBusquedaAfiliadoTest {

	private static PersonaBusqueda busqueda(String texto) {
		return new PersonaBusqueda(texto, null, null);
	}

	@Test
	@DisplayName("B1-E1 el numero exacto se convierte en un patron que lo contiene")
	void b1_e1_numero_exacto() {
		assertThat(busqueda("62000123456").patronClave()).isEqualTo("%62000123456%");
	}

	@Test
	@DisplayName("B1-E2 un fragmento queda con comodin a ambos lados: encuentra por final y por medio")
	void b1_e2_match_contiene() {
		assertThat(busqueda("3456").patronClave()).isEqualTo("%3456%");
		assertThat(busqueda("0012").patronClave()).isEqualTo("%0012%");
	}

	@Test
	@DisplayName("B1-E3 los separadores tipeados se quitan: 12-345.678/9, 12.345 y 12-345 comparan sin ellos")
	void b1_e3_sin_separadores() {
		assertThat(busqueda("12-345.678/9").patronClave()).isEqualTo("%123456789%");
		assertThat(busqueda("12.345").patronClave()).isEqualTo("%12345%");
		assertThat(busqueda("12-345").patronClave()).isEqualTo("%12345%");
		assertThat(busqueda("12 345").patronClave()).isEqualTo("%12345%");
	}

	@Test
	@DisplayName("B1-E3b un afiliado alfanumerico se compara en mayusculas y sin guion, lo tipeen como lo tipeen")
	void b1_e3b_alfanumerico() {
		assertThat(busqueda("ab-1234").patronClave()).isEqualTo("%AB1234%");
		assertThat(busqueda("AB-1234").patronClave()).isEqualTo("%AB1234%");
		assertThat(busqueda("ab1234").patronClave()).isEqualTo("%AB1234%");
		assertThat(busqueda("AB1234").patronClave()).isEqualTo("%AB1234%");
	}

	@Test
	@DisplayName("B1-E9 sin filtros la busqueda toma solo ACTIVAS y es indistinta por perfil")
	void b1_e9_filtros_por_defecto() {
		assertThat(busqueda("123").activoFiltro()).isEqualTo(1);
		assertThat(busqueda("123").perfilFiltro()).isEqualTo(-1);
		assertThat(new PersonaBusqueda("123", PersonaEstadoFiltro.TODOS, PerfilFiltro.CON_PERFIL)
				.activoFiltro()).isEqualTo(-1);
	}

	@Test
	@DisplayName("B1-E10 el patron de nombre sigue conservando espacios y puntuacion: no se mezclo con el de clave")
	void b1_e10_el_patron_de_nombre_no_cambio() {
		assertThat(busqueda("De la Cruz").patronNombre()).isEqualTo("%DE LA CRUZ%");
		assertThat(busqueda("De la Cruz").patronClave()).isEqualTo("%DELACRUZ%");
	}
}
