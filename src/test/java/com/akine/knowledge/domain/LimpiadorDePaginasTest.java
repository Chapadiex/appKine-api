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
 *
 * <p>Las reglas de relleno se prueban dentro de un libro con contenido ({@link #libroCon}): sola, una
 * pagina de relleno es mas del 40 % del texto y la red de seguridad devuelve todo sin limpiar.
 */
class LimpiadorDePaginasTest {

	private static final String ENCABEZADO = "Paquete de intervenciones de rehabilitación Módulo 2";
	private static final int PRIMERA_DE_CONTENIDO = 20;
	private static final int PAGINAS_DE_CONTENIDO = 6;

	@Test
	void descartaLaPaginaDeDerechos() throws IOException {
		assertThat(numeros(LimpiadorDePaginas.limpiar(libroCon(new PaginaDeTexto(4, recurso("m2-pagina-4-derechos.txt"))))))
				.containsExactlyElementsOf(contenido());
	}

	@Test
	void descartaElIndice() throws IOException {
		assertThat(numeros(LimpiadorDePaginas.limpiar(libroCon(new PaginaDeTexto(5, recurso("m2-pagina-5-indice.txt"))))))
				.containsExactlyElementsOf(contenido());
	}

	@Test
	void descartaMiembrosYReferencias() throws IOException {
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
		String sinTitulo = "Equipo: Ana PEREZ (fisioterapeuta, Argentina); Juan GOMEZ (médico, Chile); "
				+ "Luis DIAZ (psicólogo, Perú); Rosa VEGA (enfermera, Chile); Marta RUIZ (médica, España); "
				+ "Pedro SOTO (kinesiólogo, Uruguay).";

		List<PaginaDeTexto> limpias = LimpiadorDePaginas.limpiar(libroCon(new PaginaDeTexto(16, miembros),
				new PaginaDeTexto(17, referencias), new PaginaDeTexto(90, deInstituciones),
				new PaginaDeTexto(91, sinTitulo)));

		assertThat(numeros(limpias)).containsExactlyElementsOf(contenido());
	}

	@Test
	void pieDeEditorialNoVaciaElLibro() {
		List<PaginaDeTexto> paginas = new ArrayList<>();
		for (int i = 1; i <= 6; i++) {
			paginas.add(new PaginaDeTexto(i, "Journal of Physiotherapy " + i + "\n\nLumbar stabilization exercises "
					+ "reduced pain intensity at 12 weeks in patients with chronic low back pain.\nThe intervention "
					+ "group performed three sessions per week.\n\n© 2021 Australian Physiotherapy Association. "
					+ "Published by Elsevier B.V. All rights reserved."));
		}

		assertThat(LimpiadorDePaginas.limpiar(paginas)).hasSize(6);
		// Y en una pagina larga, el pie (ultimos renglones) no cuenta como marca de derechos.
		List<String> larga = new ArrayList<>(List.of("Titulo", "Autores", "Resumen"));
		for (int i = 0; i < 10; i++) {
			larga.add("Renglon de contenido clinico " + i + " sobre ejercicios de estabilizacion lumbar.");
		}
		larga.add("Copyright 2021 Elsevier.");
		larga.add("All rights reserved.");
		assertThat(LimpiadorDePaginas.esRelleno(larga, 10)).isFalse();
	}

	@Test
	void tablaDeDosisNoEsIndice() {
		String tabla = """
				Dosificación del ejercicio
				Series 3
				Repeticiones 10
				Descanso entre series 60
				Sesiones por semana 3
				Duración total en semanas 12
				Intensidad (escala de Borg) 13
				Carga inicial del 1RM 40""";

		assertThat(LimpiadorDePaginas.esRelleno(List.of(tabla.split("\n")), 116)).isFalse();
		assertThat(LimpiadorDePaginas.limpiar(List.of(new PaginaDeTexto(1, tabla)))).hasSize(1);
	}

	@Test
	void paginaDeSiglasNoEsMiembros() {
		String siglas = """
				Abreviaturas
				AVD (actividades, vida diaria)
				ACV (accidente, cerebrovascular)
				EPOC (enfermedad, pulmonar)
				TENS (estimulación, nerviosa)
				RM (repetición, máxima)
				IMC (índice, masa)""";

		assertThat(LimpiadorDePaginas.esRelleno(List.of(siglas.split("\n")), 10)).isFalse();
	}

	@Test
	void limpiezaQueVaciaUsaPaginasSinLimpiar() throws IOException {
		// Un libro que es solo indice: la limpieza lo dejaria vacio.
		List<PaginaDeTexto> soloIndice = List.of(new PaginaDeTexto(5, recurso("m2-pagina-5-indice.txt")));
		assertThat(LimpiadorDePaginas.limpiar(soloIndice)).isEqualTo(soloIndice);

		// Derechos y contenido de igual tamano: quitaria la mitad del texto (mas del 40 %).
		List<PaginaDeTexto> mitad = List.of(new PaginaDeTexto(4, recurso("m2-pagina-4-derechos.txt")),
				new PaginaDeTexto(9, recurso("m2-pagina-9-intervenciones.txt")));
		assertThat(LimpiadorDePaginas.limpiar(mitad)).isEqualTo(mitad);
	}

	@Test
	void quitaElEncabezadoRepetido() {
		List<PaginaDeTexto> paginas = new ArrayList<>();
		for (int n = 1; n <= 10; n++) {
			// Contenido distinto en cada pagina (con los numeros igualados, "pagina 3" y "pagina 4"
			// serian el mismo renglon).
			paginas.add(new PaginaDeTexto(n, ENCABEZADO + "\nContenido propio de la sección " + (char) ('A' + n)
					+ " sobre ejercicios.\nOtro renglón " + (char) ('a' + n) + " con más texto.\n"
					// Texto corrido para que el encabezado no sea mas del 40 % de la pagina.
					+ "El ejercicio terapéutico progresivo mejora la fuerza y la función. ".repeat(4) + "\n" + n));
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

	// ------------------------------------------------------------------ soporte

	/** Las paginas dadas mas {@value #PAGINAS_DE_CONTENIDO} copias de la pagina 9 real. */
	private static List<PaginaDeTexto> libroCon(PaginaDeTexto... relleno) throws IOException {
		List<PaginaDeTexto> libro = new ArrayList<>(List.of(relleno));
		String contenido = recurso("m2-pagina-9-intervenciones.txt");
		for (int n : contenido()) {
			libro.add(new PaginaDeTexto(n, contenido));
		}
		return libro;
	}

	private static List<Integer> contenido() {
		List<Integer> numeros = new ArrayList<>();
		for (int i = 0; i < PAGINAS_DE_CONTENIDO; i++) {
			numeros.add(PRIMERA_DE_CONTENIDO + i);
		}
		return numeros;
	}

	private static List<Integer> numeros(List<PaginaDeTexto> paginas) {
		return paginas.stream().map(PaginaDeTexto::numero).toList();
	}

	private static String recurso(String nombre) throws IOException {
		try (InputStream in = LimpiadorDePaginasTest.class.getResourceAsStream("/knowledge/limpiador/" + nombre)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}
}
