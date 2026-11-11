package com.akine.knowledge.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Las paginas reales son del Modulo 2 del Paquete de intervenciones de rehabilitacion de la OMS
 * (CC BY-NC-SA 3.0 IGO), extraidas con PDFBox ordenado por posicion: texto publico, sin datos de
 * pacientes.
 */
class LimpiadorDePaginasTest {

	private static final String ENCABEZADO = "Paquete de intervenciones de rehabilitación Módulo 2";

	@Test
	void descartaLaPaginaDeDerechos() throws IOException {
		List<PaginaDeTexto> limpias = LimpiadorDePaginas.limpiar(List.of(
				new PaginaDeTexto(4, recurso("m2-pagina-4-derechos.txt")),
				new PaginaDeTexto(9, recurso("m2-pagina-9-intervenciones.txt"))));

		assertThat(limpias).extracting(PaginaDeTexto::numero).containsExactly(9);
	}

	@Test
	void descartaElIndice() throws IOException {
		List<PaginaDeTexto> limpias = LimpiadorDePaginas.limpiar(List.of(
				new PaginaDeTexto(5, recurso("m2-pagina-5-indice.txt")),
				new PaginaDeTexto(9, recurso("m2-pagina-9-intervenciones.txt"))));

		assertThat(limpias).extracting(PaginaDeTexto::numero).containsExactly(9);
	}

	@Test
	void descartaMiembrosYReferencias() {
		String miembros = """
				1.3 Miembros de los grupos de trabajo
				Los siguientes expertos han contribuido a la elaboración del paquete.
				Miembros del grupo de trabajo técnico
				Ana PEREZ (fisioterapeuta, Argentina); Juan GOMEZ (médico fisiatra, Chile)""";
		String referencias = """
				1.4 Referencias
				1. Wu A, March L, Zheng X. Global low back pain prevalence. Ann Transl Med.
				2020;8(6):299.
				2. Dagenais S, Caro J, Haldeman S. A systematic review of low back pain cost.
				Spine. 2008;8(1):8–20.
				3. Schofield D, Callander E, Shrestha R. Labour force participation.
				Spine. 2012;37(13):1156–63.""";

		String deInstituciones = """
				6.4 Referencias
				1.\t Normas de ortoprotésica de la OMS. Ginebra: Organización Mundial de la Salud; 2017.
				2.\t Integrated care for older people: guidelines on community-level interventions.
				Ginebra: Organización Mundial de la Salud; 2022.
				3.\t Guía de intervención mhGAP. Versión 2.0. Ginebra: Organización Mundial de la Salud.""";

		List<PaginaDeTexto> limpias = LimpiadorDePaginas.limpiar(List.of(
				new PaginaDeTexto(16, miembros), new PaginaDeTexto(17, referencias),
				new PaginaDeTexto(90, deInstituciones),
				new PaginaDeTexto(18, "La lumbalgia es una afección frecuente.")));

		assertThat(limpias).extracting(PaginaDeTexto::numero).containsExactly(18);
	}

	@Test
	void quitaElEncabezadoRepetido() {
		List<PaginaDeTexto> paginas = new ArrayList<>();
		for (int n = 1; n <= 10; n++) {
			// Contenido distinto en cada pagina (con los numeros igualados, "pagina 3" y "pagina 4"
			// serian el mismo renglon).
			paginas.add(new PaginaDeTexto(n, ENCABEZADO + "\nContenido propio de la sección " + (char) ('A' + n)
					+ " sobre ejercicios.\nOtro renglón " + (char) ('a' + n) + " con más texto.\n" + n));
		}

		List<PaginaDeTexto> limpias = LimpiadorDePaginas.limpiar(paginas);

		assertThat(limpias).hasSize(10).allSatisfy(p -> {
			assertThat(p.texto()).doesNotContain(ENCABEZADO);
			assertThat(p.texto()).contains("Contenido propio de la sección " + (char) ('A' + p.numero()));
			// el numero de pagina suelto tambien es un pie repetido
			assertThat(p.texto().strip()).doesNotEndWith("\n" + p.numero());
		});
	}

	@Test
	void noQuitaRenglonesEnLibrosCortos() {
		List<PaginaDeTexto> paginas = List.of(
				new PaginaDeTexto(1, ENCABEZADO + "\nUno."),
				new PaginaDeTexto(2, ENCABEZADO + "\nDos."),
				new PaginaDeTexto(3, ENCABEZADO + "\nTres."));

		assertThat(LimpiadorDePaginas.limpiar(paginas)).allSatisfy(p -> assertThat(p.texto()).contains(ENCABEZADO));
	}

	@Test
	void uneRotulosVerticales() {
		String pagina = """
				Fisioterapeuta A ct iv id ad e s d e la v id a d ia ri a
				Objetivo: Actividades de la vida diaria""";
		assertThat(LimpiadorDePaginas.limpiar(List.of(new PaginaDeTexto(11, pagina))).get(0).texto())
				.startsWith("Fisioterapeuta Actividades de la vida diaria\n");

		// Sin la frase entera en la pagina, queda una sola palabra.
		assertThat(LimpiadorDePaginas.unirRotulos("E d u ca ci ó n y a ct iv id ad la b o ra l", "nada"))
				.isEqualTo("Educaciónyactividadlaboral");
		// Palabras cortas de verdad no se unen.
		assertThat(LimpiadorDePaginas.unirRotulos("que se le da a la persona", "nada"))
				.isEqualTo("que se le da a la persona");
	}

	@Test
	void conservaPaginasDeContenido() throws IOException {
		String texto = recurso("m2-pagina-9-intervenciones.txt");

		List<PaginaDeTexto> limpias = LimpiadorDePaginas.limpiar(List.of(new PaginaDeTexto(9, texto)));

		assertThat(limpias).hasSize(1);
		assertThat(limpias.get(0).numero()).isEqualTo(9);
		assertThat(limpias.get(0).texto()).contains("Psicoterapia cognitivo-conductual")
				.contains("Evaluación de la capacidad de ejercicio");
	}

	private static String recurso(String nombre) throws IOException {
		try (InputStream in = LimpiadorDePaginasTest.class.getResourceAsStream("/knowledge/limpiador/" + nombre)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
