package com.akine.knowledge.application;

import com.akine.TestcontainersConfiguration;
import com.akine.knowledge.application.LibroService.ArchivoSubido;
import com.akine.knowledge.application.LibroService.DatosDeLibro;
import com.akine.knowledge.domain.port.AlmacenVectorial;
import com.akine.knowledge.domain.port.AlmacenVectorial.Coincidencia;
import com.akine.knowledge.spi.BibliotecaDirectory;
import com.akine.knowledge.spi.CaminoBusqueda;
import com.akine.knowledge.spi.PasajeRecuperado;
import com.akine.knowledge.spi.ResultadoBusqueda;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * La busqueda contra MySQL, con el modelo de prueba (bolsa de palabras) y el worker apagado: los
 * libros se procesan a mano con {@code procesarSiguiente}. Cada test usa organizaciones y palabras
 * inventadas propias; las aserciones miran solo esas organizaciones.
 *
 * <p>MySQL FULLTEXT ve solo filas confirmadas e ignora palabras de menos de 3 letras y las
 * palabras vacias: las palabras de prueba son de 10 letras al azar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
		"akine.archivos.base-dir=target/test-archivos",
		"akine.biblioteca.enabled=true",
		"akine.biblioteca.modelo.tipo=prueba",
		"akine.biblioteca.worker.enabled=false"})
class BusquedaIT {

	private static final String RELLENO = "La rehabilitacion de rodilla combina ejercicios de fuerza, "
			+ "movilidad y control motor progresivos segun la tolerancia del paciente.";

	@Autowired ProcesamientoDeLibroService procesamiento;
	@Autowired LibroService libros;
	@Autowired BusquedaService busqueda;
	@Autowired BibliotecaDirectory directory;
	@Autowired AlmacenVectorial almacen;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean BibliotecaAcceso acceso;

	// ------------------------------------------------------------------ escenarios

	@Test
	void una_consulta_desde_A_nunca_devuelve_pasajes_de_B_aunque_B_tenga_el_texto_exacto() throws Exception {
		long a = organizacion();
		long b = organizacion();
		String deA = palabra();
		String deB = palabra();
		long libroA = procesado(a, pdf(pagina(deA, 1), pagina(deA, 2)));
		long libroB = procesado(b, pdf(pagina(deB, 1)));
		String textoDeB = jdbc.queryForObject(
				"SELECT texto FROM libro_fragmento WHERE libro_id = ? ORDER BY orden LIMIT 1", String.class, libroB);

		// El texto exacto de un pedazo de B: en B daria coseno 1.
		String consulta = textoDeB.substring(0, Math.min(600, textoDeB.length()));
		ResultadoBusqueda desdeA = busqueda.buscar(actor(a), a, consulta, 20);
		assertThat(desdeA.camino()).isEqualTo(CaminoBusqueda.VECTOR);
		assertThat(desdeA.pasajes()).isNotEmpty()
				.allSatisfy(p -> assertThat(p.libroId()).isEqualTo(libroA))
				.noneSatisfy(p -> assertThat(p.texto()).containsIgnoringCase(deB));
		assertThat(directory.buscar(a, deB, 20).pasajes())
				.allSatisfy(p -> assertThat(p.libroId()).isEqualTo(libroA));
		assertThat(almacen.porPalabras(a, deB, 20)).isEmpty();

		// Del lado de B el mismo texto si aparece, y primero.
		ResultadoBusqueda desdeB = busqueda.buscar(actor(b), b, consulta, 5);
		assertThat(desdeB.pasajes()).isNotEmpty()
				.allSatisfy(p -> assertThat(p.libroId()).isEqualTo(libroB));
		assertThat(desdeB.pasajes().get(0).puntaje()).isGreaterThan(0.818);
		assertThat(desdeB.pasajes().get(0).superaCorte()).isTrue();
		assertThat(almacen.porPalabras(b, deB, 5)).extracting(Coincidencia::fragmentoId)
				.containsExactlyInAnyOrderElementsOf(fragmentosCon(libroB, deB));
	}

	@Test
	void el_pedazo_sospechoso_no_aparece_aunque_sea_el_mas_parecido() throws Exception {
		long org = organizacion();
		String sospechosa = palabra();
		long libro = procesado(org, pdf(pagina(sospechosa, 1), pagina(palabra(), 2), pagina(palabra(), 3)));
		List<Long> marcados = fragmentosCon(libro, sospechosa);
		assertThat(marcados).isNotEmpty();
		// Con su vector intacto: el filtro es la marca, no la falta de vector.
		jdbc.update("UPDATE libro_fragmento SET sospechoso = true WHERE libro_id = ? AND texto LIKE ?",
				libro, "%" + sospechosa + "%");

		ResultadoBusqueda r = busqueda.buscar(actor(org), org, sospechosa, 20);

		assertThat(r.pasajes()).isNotEmpty()
				.noneSatisfy(p -> assertThat(p.texto()).containsIgnoringCase(sospechosa));
		assertThat(almacen.porPalabras(org, sospechosa, 20)).isEmpty();
	}

	@Test
	void un_pedazo_de_otro_modelo_no_sale_por_vector_y_si_por_palabras() throws Exception {
		long org = organizacion();
		String vieja = palabra();
		long libro = procesado(org, pdf(pagina(vieja, 1), pagina(palabra(), 2), pagina(palabra(), 3)));
		List<Long> deOtroModelo = fragmentosCon(libro, vieja);
		assertThat(deOtroModelo).isNotEmpty();
		jdbc.update("UPDATE libro_fragmento SET modelo_embeddings = 'otro' WHERE libro_id = ? AND texto LIKE ?",
				libro, "%" + vieja + "%");

		ResultadoBusqueda r = busqueda.buscar(actor(org), org, vieja, 20);

		assertThat(r.camino()).isEqualTo(CaminoBusqueda.VECTOR);
		assertThat(r.pasajes()).isNotEmpty()
				.noneSatisfy(p -> assertThat(p.texto()).containsIgnoringCase(vieja));
		assertThat(almacen.porPalabras(org, vieja, 20)).extracting(Coincidencia::fragmentoId)
				.containsExactlyInAnyOrderElementsOf(deOtroModelo);
	}

	@Test
	void durante_el_reemplazo_responde_el_viejo_y_al_terminar_solo_el_nuevo() throws Exception {
		long org = organizacion();
		String vieja = palabra();
		String nueva = palabra();
		long original = procesado(org, pdf(pagina(vieja, 1), pagina(vieja, 2)));
		assertThat(libroIds(busqueda.buscar(actor(org), org, vieja, 5))).containsOnly(original);

		long reemplazo = libros.reemplazar(actor(org), org, original,
				new ArchivoSubido("nuevo.pdf", pdf(pagina(nueva, 1), pagina(nueva, 2))), true).id();

		// Pendiente: sigue el viejo, el nuevo todavia no.
		assertThat(libroIds(busqueda.buscar(actor(org), org, vieja, 5))).containsOnly(original);
		assertThat(textos(busqueda.buscar(actor(org), org, nueva, 5)))
				.noneSatisfy(t -> assertThat(t).containsIgnoringCase(nueva));

		procesarHasta(reemplazo);

		ResultadoBusqueda despues = busqueda.buscar(actor(org), org, nueva, 5);
		assertThat(libroIds(despues)).containsOnly(reemplazo);
		assertThat(textos(despues)).anySatisfy(t -> assertThat(t).containsIgnoringCase(nueva));
		assertThat(libroIds(busqueda.buscar(actor(org), org, vieja, 5))).containsOnly(reemplazo);
		assertThat(almacen.porPalabras(org, vieja, 5)).isEmpty();
	}

	@Test
	void desactivar_un_libro_lo_saca_de_la_consulta_siguiente() throws Exception {
		long org = organizacion();
		String clave = palabra();
		long queda = procesado(org, pdf(pagina(palabra(), 1)));
		long sale = procesado(org, pdf(pagina(clave, 1), pagina(clave, 2)));
		assertThat(libroIds(busqueda.buscar(actor(org), org, clave, 5))).contains(sale);

		libros.desactivar(actor(org), org, sale);

		// k = 1: con la cache vieja el mejor seria un pedazo del desactivado, que la lectura del texto
		// descarta, y quedaria vacio. Asi se ve que la cache se recargo y no solo el filtro final.
		ResultadoBusqueda r = busqueda.buscar(actor(org), org, clave, 1);
		assertThat(libroIds(r)).containsExactly(queda);
		assertThat(almacen.porPalabras(org, clave, 5)).isEmpty();
	}

	@Test
	void una_biblioteca_vacia_no_devuelve_pasajes_y_una_consulta_en_blanco_se_rechaza() {
		long org = organizacion();

		ResultadoBusqueda r = busqueda.buscar(actor(org), org, "ejercicios de rodilla", 5);

		assertThat(r.pasajes()).isEmpty();
		assertThat(r.alcanza()).isFalse();
		assertThat(almacen.porPalabras(org, "rodilla", 5)).isEmpty();
		assertThatThrownBy(() -> busqueda.buscar(actor(org), org, "   ", 5))
				.isInstanceOf(IllegalArgumentException.class);
	}

	// ------------------------------------------------------------------ soporte

	private long organizacion() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", "Centro " + sufijo, "busqueda-it-" + sufijo);
		return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class,
				"busqueda-it-" + sufijo);
	}

	private static OperatingActor actor(long org) {
		return new OperatingActor(99L, false, org);
	}

	/** Diez letras al azar: no colisiona con nada de la base ni es palabra vacia de FULLTEXT. */
	private static String palabra() {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 10; i++) {
			sb.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
		}
		return sb.toString();
	}

	private long procesado(long org, byte[] pdf) {
		long id = libros.subir(actor(org), org, new DatosDeLibro("Libro " + UUID.randomUUID(), null, null, null),
				new ArchivoSubido("libro.pdf", pdf), true).id();
		procesarHasta(id);
		assertThat(jdbc.queryForObject("SELECT estado FROM libro WHERE id = ?", String.class, id))
				.isEqualTo("LISTO");
		return id;
	}

	/** La cola es global: puede procesar antes libros que otras clases dejaron pendientes. */
	private void procesarHasta(long id) {
		for (int i = 0; i < 200; i++) {
			String e = jdbc.queryForObject("SELECT estado FROM libro WHERE id = ?", String.class, id);
			if ("LISTO".equals(e) || "ERROR".equals(e) || !procesamiento.procesarSiguiente(Instant.now())) {
				return;
			}
		}
	}

	private List<Long> fragmentosCon(long libro, String palabra) {
		return jdbc.queryForList("SELECT id FROM libro_fragmento WHERE libro_id = ? AND texto LIKE ? ORDER BY id",
				Long.class, libro, "%" + palabra + "%");
	}

	private static List<Long> libroIds(ResultadoBusqueda r) {
		return r.pasajes().stream().map(PasajeRecuperado::libroId).toList();
	}

	private static List<String> textos(ResultadoBusqueda r) {
		return r.pasajes().stream().map(p -> p.texto().toLowerCase(Locale.ROOT)).toList();
	}

	/** Unos 1300 caracteres por pagina, con la palabra de la prueba al principio. */
	private static String pagina(String palabra, int n) {
		StringBuilder sb = new StringBuilder("Capitulo " + n + ". " + palabra + ". ");
		while (sb.length() < 1300) {
			sb.append(RELLENO).append(' ');
		}
		return sb.toString();
	}

	private static byte[] pdf(String... paginas) throws IOException {
		try (PDDocument doc = new PDDocument()) {
			PDType1Font fuente = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
			for (String texto : paginas) {
				PDPage page = new PDPage();
				doc.addPage(page);
				try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
					cs.beginText();
					cs.setFont(fuente, 9);
					cs.setLeading(11);
					cs.newLineAtOffset(40, 750);
					StringBuilder linea = new StringBuilder();
					for (String palabra : texto.split(" ")) {
						if (linea.length() + palabra.length() > 100) {
							cs.showText(linea.toString().strip());
							cs.newLine();
							linea.setLength(0);
						}
						linea.append(palabra).append(' ');
					}
					cs.showText(linea.toString().strip());
					cs.endText();
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}
}
