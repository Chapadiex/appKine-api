package com.akine.knowledge.application;

import com.akine.TestcontainersConfiguration;
import com.akine.knowledge.application.LibroService.ArchivoSubido;
import com.akine.knowledge.application.LibroService.DatosDeLibro;
import com.akine.knowledge.domain.port.BibliotecaVersionPort;
import com.akine.knowledge.domain.port.ModeloEmbeddings;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El reindexado contra MySQL, con el modelo de prueba y el worker apagado: {@code reindexarLote}
 * se llama a mano. La base se comparte con otras clases de IT: las aserciones miran solo las filas
 * de este test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
		"akine.archivos.base-dir=target/test-archivos",
		"akine.biblioteca.enabled=true",
		"akine.biblioteca.modelo.tipo=prueba",
		"akine.biblioteca.worker.enabled=false"})
class ReindexadoIT {

	private static final String RELLENO = "La rehabilitacion de rodilla combina ejercicios de fuerza, "
			+ "movilidad y control motor progresivos segun la tolerancia del paciente.";

	@Autowired ProcesamientoDeLibroService procesamiento;
	@Autowired ReindexadoService reindexado;
	@Autowired LibroService libros;
	@Autowired ModeloEmbeddings modelo;
	@Autowired BibliotecaVersionPort version;
	@Autowired JdbcTemplate jdbc;
	@MockitoBean BibliotecaAcceso acceso;

	long orgId;
	OperatingActor actor;

	@BeforeEach
	void organizacion() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", "Centro " + sufijo, "reindex-it-" + sufijo);
		orgId = jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class,
				"reindex-it-" + sufijo);
		actor = new OperatingActor(99L, false, orgId);
	}

	@Test
	void reindexa_los_pedazos_de_otro_modelo_y_no_toca_sospechosos_ni_libros_inactivos() throws Exception {
		long activo = procesado(pdf(pagina(1), pagina(2), pagina(3)));
		long inactivo = procesado(pdf(pagina(4), pagina(5)));
		jdbc.update("UPDATE libro SET activo = false WHERE id = ?", inactivo);
		// Un pedazo sospechoso del libro activo: sin vector, no se reindexa.
		Long sospechoso = jdbc.queryForObject(
				"SELECT id FROM libro_fragmento WHERE libro_id = ? ORDER BY orden LIMIT 1", Long.class, activo);
		jdbc.update("UPDATE libro_fragmento SET sospechoso = true, vector = NULL, modelo_embeddings = NULL WHERE id = ?",
				sospechoso);
		jdbc.update("UPDATE libro_fragmento SET modelo_embeddings = 'modelo-viejo' WHERE libro_id IN (?, ?) AND sospechoso = false",
				activo, inactivo);
		long normales = jdbc.queryForObject(
				"SELECT COUNT(*) FROM libro_fragmento WHERE libro_id = ? AND sospechoso = false", Long.class, activo);
		assertThat(normales).isPositive();
		long antes = version.actual(orgId);

		int total = 0;
		for (int n; (n = reindexado.reindexarLote()) > 0; ) {
			total += n;
		}

		assertThat(total).isGreaterThanOrEqualTo((int) normales);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM libro_fragmento
				 WHERE libro_id = ? AND sospechoso = false
				   AND (modelo_embeddings <> ? OR vector IS NULL)""", Integer.class, activo, modelo.id())).isZero();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM libro_fragmento WHERE libro_id = ? AND sospechoso = false AND modelo_embeddings = ?",
				Long.class, activo, modelo.id())).isEqualTo(normales);
		assertThat(jdbc.queryForMap("SELECT vector, modelo_embeddings FROM libro_fragmento WHERE id = ?", sospechoso))
				.containsEntry("vector", null).containsEntry("modelo_embeddings", null);
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM libro_fragmento WHERE libro_id = ? AND modelo_embeddings = 'modelo-viejo'",
				Long.class, inactivo)).isPositive();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM libro_fragmento WHERE libro_id = ? AND modelo_embeddings = ?",
				Long.class, inactivo, modelo.id())).isZero();
		assertThat(version.actual(orgId)).isGreaterThan(antes);
	}

	private long procesado(byte[] pdf) {
		long id = libros.subir(actor, orgId, new DatosDeLibro("Libro " + UUID.randomUUID(), null, null, null),
				new ArchivoSubido("libro.pdf", pdf), true).id();
		for (int i = 0; i < 100; i++) {
			String e = jdbc.queryForObject("SELECT estado FROM libro WHERE id = ?", String.class, id);
			if ("LISTO".equals(e) || "ERROR".equals(e) || !procesamiento.procesarSiguiente(Instant.now())) {
				break;
			}
		}
		assertThat(jdbc.queryForObject("SELECT estado FROM libro WHERE id = ?", String.class, id))
				.isEqualTo("LISTO");
		return id;
	}

	private static String pagina(int n) {
		StringBuilder sb = new StringBuilder("Capitulo " + n + ". ");
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
					// Lineas cortadas en espacios: una palabra partida no la reconoce el detector.
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
