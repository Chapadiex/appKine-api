package com.akine.organization.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Derivacion del slug del tenant.
 *
 * <p>El dominio es argentino: acentos y "ñ" entran por el nombre del centro todos los dias y
 * tienen que salir como un slug usable en una URL. Los acentos se DESCARTAN, no se traducen:
 * asi dos nombres iguales tipeados distinto producen el mismo slug y la colision la resuelve
 * el sufijo numerico, en vez de convivir dos tenants que parecen el mismo.
 */
class SlugsTest {

	private static final Predicate<String> NADA_OCUPADO = candidato -> false;

	@ParameterizedTest
	@CsvSource({
			"Centro Kine Norte,               centro-kine-norte",
			"Kinesiologia Nuñez,         kinesiologia-nunez",
			"Rehabilitación Integral,    rehabilitacion-integral",
			"Kinésica Belgrano,          kinesica-belgrano",
			"  Espacios   Multiples  ,        espacios-multiples",
			"Centro (Kine) 2024!,             centro-kine-2024",
			"---Guiones---Sueltos---,         guiones-sueltos",
			"MAYUSCULAS,                      mayusculas",
			"Salud & Movimiento,              salud-movimiento"
	})
	@DisplayName("La normalizacion baja a minusculas, quita acentos y deja solo a-z0-9 con guiones")
	void la_normalizacion_produce_un_slug_usable(String nombre, String esperado) {
		assertThat(Slugs.normalizar(nombre)).isEqualTo(esperado);
		assertThat(Slugs.derivar(nombre, NADA_OCUPADO)).isEqualTo(esperado);
	}

	@Test
	@DisplayName("La enie y las vocales acentuadas no cambian el slug respecto de su forma sin "
			+ "acento: 'Nunez' y 'Nuñez' producen el mismo")
	void los_acentos_se_descartan_no_se_traducen() {
		assertThat(Slugs.normalizar("Kinesiologia Nuñez"))
				.isEqualTo(Slugs.normalizar("Kinesiologia Nunez"));
	}

	@Test
	@DisplayName("Un nombre sin ningun caracter latino usable cae en un prefijo estable, "
			+ "nunca en vacio")
	void un_nombre_no_latino_no_deja_el_slug_vacio() {
		// Devolver vacio violaria el NOT NULL de organization.slug.
		assertThat(Slugs.normalizar("中文诊所")).isEqualTo("org");
		assertThat(Slugs.normalizar("!!! ???")).isEqualTo("org");
	}

	@Test
	@DisplayName("Un nombre largo se recorta a 64 caracteres")
	void un_nombre_largo_se_recorta() {
		String largo = "Centro de Kinesiologia y Rehabilitacion Integral del Norte de la "
				+ "Provincia de Cordoba";

		String slug = Slugs.normalizar(largo);

		assertThat(slug).hasSize(64);
		assertThat(slug).startsWith("centro-de-kinesiologia");
	}

	@Test
	@DisplayName("Un slug ocupado se resuelve con el primer sufijo numerico libre")
	void un_slug_ocupado_recibe_sufijo() {
		Set<String> ocupados = Set.of("centro-kine-norte", "centro-kine-norte-2");

		assertThat(Slugs.derivar("Centro Kine Norte", ocupados::contains))
				.isEqualTo("centro-kine-norte-3");
	}

	@Test
	@DisplayName("El sufijo se agrega recortando la base: el slug nunca supera los 64 caracteres")
	void el_sufijo_no_desborda_el_largo_maximo() {
		String largo = "Centro de Kinesiologia y Rehabilitacion Integral del Norte de la "
				+ "Provincia de Cordoba";
		Set<String> ocupados = new HashSet<>();
		ocupados.add(Slugs.normalizar(largo));

		String slug = Slugs.derivar(largo, ocupados::contains);

		assertThat(slug).hasSizeLessThanOrEqualTo(64).endsWith("-2");
	}

	@Test
	@DisplayName("Agotados los sufijos legibles se devuelve el ultimo y decide la restriccion "
			+ "unica, en vez de inventar un slug ilegible")
	void agotados_los_intentos_decide_la_restriccion() {
		// Preferimos un 409 explicito antes que un identificador al azar que nadie puede leer
		// por telefono cuando llama a soporte.
		String slug = Slugs.derivar("Centro Kine Norte", candidato -> true);

		assertThat(slug).isEqualTo("centro-kine-norte-50");
	}

	@Test
	@DisplayName("Un slug disponible no consulta mas de una vez la ocupacion")
	void un_slug_libre_se_devuelve_de_una() {
		int[] consultas = {0};

		String slug = Slugs.derivar("Centro Kine Norte", candidato -> {
			consultas[0]++;
			return false;
		});

		assertThat(slug).isEqualTo("centro-kine-norte");
		assertThat(consultas[0]).isEqualTo(1);
	}
}
