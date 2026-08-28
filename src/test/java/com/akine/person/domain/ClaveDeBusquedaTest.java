package com.akine.person.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La normalizacion de las claves por las que se busca y se compara una Persona.
 *
 * <p>Es la clase mas chica del modulo y la que mas consecuencias tiene: de ella dependen el
 * unique de documento, la deteccion de duplicados y las cuatro columnas por las que busca el
 * mostrador. Un cambio silencioso aca hace que personas ya cargadas dejen de encontrarse, sin
 * ningun error visible.
 */
@DisplayName("ClaveDeBusqueda")
class ClaveDeBusquedaTest {

	@Nested
	@DisplayName("Documento")
	class Documento {

		@Test
		@DisplayName("Los separadores no cambian el documento: 12.345.678 y 12345678 son el mismo")
		void los_separadores_no_cuentan() {
			assertThat(ClaveDeBusqueda.deDocumento("12.345.678"))
					.isEqualTo(ClaveDeBusqueda.deDocumento("12 345 678"))
					.isEqualTo(ClaveDeBusqueda.deDocumento("12345678"))
					.isEqualTo("12345678");
		}

		@Test
		@DisplayName("Un pasaporte se compara en mayusculas y sin acentos")
		void pasaporte_normalizado() {
			assertThat(ClaveDeBusqueda.deDocumento("ab-123.456")).isEqualTo("AB123456");
		}

		@Test
		@DisplayName("Sin documento, o con basura que se limpia entera, la clave es null")
		void sin_documento_la_clave_es_nula() {
			// Ese null es el que deja la fila FUERA del unique, y eso es lo buscado: varias
			// personas sin documento conviven sin chocar porque en MySQL varios NULL no colisionan.
			assertThat(ClaveDeBusqueda.deDocumento(null)).isNull();
			assertThat(ClaveDeBusqueda.deDocumento("   ")).isNull();
			assertThat(ClaveDeBusqueda.deDocumento("---")).isNull();
		}
	}

	@Nested
	@DisplayName("Nombre")
	class Nombre {

		@Test
		@DisplayName("Los acentos no cuentan: Perez y Pérez son la misma clave")
		void los_acentos_no_cuentan() {
			assertThat(ClaveDeBusqueda.deNombre("Pérez")).isEqualTo(ClaveDeBusqueda.deNombre("Perez"));
		}

		@Test
		@DisplayName("Los espacios internos SI cuentan: Ana Maria no es Anamaria")
		void los_espacios_del_nombre_se_conservan() {
			// Colapsarlos produciria falsos duplicados que despues nadie entiende. Es la diferencia
			// deliberada con la clave de documento.
			assertThat(ClaveDeBusqueda.deNombre("Ana Maria")).isEqualTo("ANA MARIA");
			assertThat(ClaveDeBusqueda.deNombre("Ana Maria"))
					.isNotEqualTo(ClaveDeBusqueda.deNombre("Anamaria"));
		}

		@Test
		@DisplayName("Los espacios de mas se colapsan a uno solo")
		void los_espacios_repetidos_se_colapsan() {
			assertThat(ClaveDeBusqueda.deNombre("  Ana    Maria  ")).isEqualTo("ANA MARIA");
		}
	}

	@Nested
	@DisplayName("Telefono")
	class Telefono {

		@Test
		@DisplayName("Solo los digitos: +54 11 5555-0000 y 541155550000 son el mismo telefono")
		void solo_los_digitos() {
			assertThat(ClaveDeBusqueda.deTelefono("+54 11 5555-0000")).isEqualTo("541155550000");
		}

		@Test
		@DisplayName("Sin telefono la clave es null, y no una cadena vacia")
		void sin_telefono_la_clave_es_nula() {
			// Importa: la consulta de coincidencias pregunta IS NOT NULL, y una cadena vacia
			// haria que todas las personas sin telefono coincidieran entre si.
			assertThat(ClaveDeBusqueda.deTelefono(null)).isNull();
			assertThat(ClaveDeBusqueda.deTelefono("sin datos")).isNull();
		}
	}
}
