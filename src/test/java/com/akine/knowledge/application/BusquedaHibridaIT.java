package com.akine.knowledge.application;

import com.akine.TestcontainersConfiguration;
import com.akine.knowledge.application.LibroService.ArchivoSubido;
import com.akine.knowledge.application.LibroService.DatosDeLibro;
import com.akine.knowledge.spi.CaminoBusqueda;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La busqueda hibrida contra MySQL con el modelo de prueba y el worker apagado. Dos organizaciones
 * con libros: buscar en una nunca devuelve fragmentos de la otra, ni por vector ni por palabras.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
		"akine.archivos.base-dir=target/test-archivos",
		"akine.biblioteca.enabled=true",
		"akine.biblioteca.modelo.tipo=prueba",
		"akine.biblioteca.worker.enabled=false"})
class BusquedaHibridaIT {

	private static final String RELLENO = "La rehabilitacion de rodilla combina ejercicios de fuerza, "
			+ "movilidad y control motor progresivos segun la tolerancia del paciente.";

	@Autowired ProcesamientoDeLibroService procesamiento;
	@Autowired LibroService libros;
	@Autowired BusquedaHibrida hibrida;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean BibliotecaAcceso acceso;

	@Test
	void buscar_en_A_nunca_devuelve_fragmentos_de_B_ni_por_vector_ni_por_palabras() throws Exception {
		long a = organizacion();
		long b = organizacion();
		String deA = palabra();
		String deB = palabra();
		long libroA = procesado(a, pdf(pagina(deA, 1), pagina(deA, 2)));
		long libroB = procesado(b, pdf(pagina(deB, 1)));
		String textoDeB = jdbc.queryForObject(
				"SELECT texto FROM libro_fragmento WHERE libro_id = ? ORDER BY orden LIMIT 1", String.class, libroB);
		String exacto = textoDeB.substring(0, Math.min(600, textoDeB.length()));

		// Por vector: el texto exacto de un pedazo de B.
		ResultadoHibrido porVector = hibrida.buscar(a, List.of(exacto), deB, Set.of());
		assertThat(porVector.camino()).isEqualTo(CaminoBusqueda.VECTOR);
		assertThat(porVector.pasajes()).isNotEmpty()
				.allSatisfy(p -> assertThat(p.pasaje().libroId()).isEqualTo(libroA))
				.noneSatisfy(p -> assertThat(p.pasaje().texto()).containsIgnoringCase(deB));

		// Por palabras: la palabra inventada que solo existe en B.
		ResultadoHibrido porPalabras = hibrida.buscar(a, List.of(deB), deB, Set.of());
		assertThat(porPalabras.pasajes())
				.allSatisfy(p -> assertThat(p.pasaje().libroId()).isEqualTo(libroA))
				.noneMatch(PasajeFusionado::coincidePalabra);

		// Del lado de B el mismo texto si aparece, con evidencia.
		ResultadoHibrido desdeB = hibrida.buscar(b, List.of(exacto), deB, Set.of());
		assertThat(desdeB.pasajes()).isNotEmpty()
				.allSatisfy(p -> assertThat(p.pasaje().libroId()).isEqualTo(libroB));
		assertThat(desdeB.hayEvidencia()).isTrue();
	}

	// ------------------------------------------------------------------ soporte

	private long organizacion() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + sufijo, "hibrida-it-" + sufijo);
		return jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class,
				"hibrida-it-" + sufijo);
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
		for (int i = 0; i < 200; i++) {
			String e = jdbc.queryForObject("SELECT estado FROM libro WHERE id = ?", String.class, id);
			if ("LISTO".equals(e) || "ERROR".equals(e) || !procesamiento.procesarSiguiente(Instant.now())) {
				break;
			}
		}
		assertThat(jdbc.queryForObject("SELECT estado FROM libro WHERE id = ?", String.class, id))
				.isEqualTo("LISTO");
		return id;
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
