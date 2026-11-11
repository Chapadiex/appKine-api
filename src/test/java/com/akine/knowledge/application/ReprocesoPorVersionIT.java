package com.akine.knowledge.application;

import com.akine.TestcontainersConfiguration;
import com.akine.knowledge.application.LibroService.ArchivoSubido;
import com.akine.knowledge.application.LibroService.DatosDeLibro;
import com.akine.knowledge.domain.exception.DocumentoIlegibleException;
import com.akine.knowledge.domain.port.BibliotecaVersionPort;
import com.akine.knowledge.domain.port.ExtractorDeTexto;
import com.akine.knowledge.spi.BibliotecaDirectory;
import com.akine.knowledge.spi.PasajeRecuperado;
import com.akine.platform.spi.archivo.AlmacenDeArchivos;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * El reproceso por version contra MySQL, con el modelo de prueba y el worker apagado: el
 * encolado y el procesamiento se llaman a mano, como haria el worker en dos vueltas.
 *
 * <p>El libro "viejo" se fabrica procesandolo con la ingesta actual y despues bajandole la
 * version a 1 y agregandole un fragmento de la pagina de derechos, como lo dejaba la ingesta v1.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
		"akine.archivos.base-dir=target/test-archivos",
		"akine.biblioteca.enabled=true",
		"akine.biblioteca.modelo.tipo=prueba",
		"akine.biblioteca.worker.enabled=false"})
class ReprocesoPorVersionIT {

	private static final String RELLENO = "La rehabilitacion de rodilla combina ejercicios de fuerza, "
			+ "movilidad y control motor progresivos segun la tolerancia del paciente.";
	private static final String DERECHOS = """
			ISBN 978-92-4-007833-8 (version electronica)
			Organizacion Mundial de la Salud 2023
			Algunos derechos reservados. Esta obra esta disponible en virtud de la licencia
			Reconocimiento-NoComercial-CompartirIgual de Creative Commons.
			Catalogacion (CIP). Notas de descargo generales.""";
	private static final String CONSULTA_DERECHOS = "derechos reservados licencia Creative Commons";

	@Autowired ProcesamientoDeLibroService procesamiento;
	@Autowired ReprocesoPorVersionService reproceso;
	@Autowired LibroService libros;
	@Autowired BibliotecaDirectory directory;
	@Autowired AlmacenDeArchivos almacen;
	@Autowired JdbcTemplate jdbc;
	@MockitoSpyBean BibliotecaVersionPort version;
	@MockitoSpyBean ExtractorDeTexto extractor;
	@MockitoBean BibliotecaAcceso acceso;

	long orgId;
	OperatingActor actor;

	@BeforeEach
	void organizacion() {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + sufijo, "repro-it-" + sufijo);
		orgId = jdbc.queryForObject("SELECT id FROM organization WHERE slug = ?", Long.class,
				"repro-it-" + sufijo);
		actor = new OperatingActor(99L, false, orgId);
	}

	@Test
	void el_indice_viejo_responde_durante_el_reproceso_y_al_final_queda_uno_solo_en_la_version_vigente()
			throws Exception {
		long viejo = libroEnVersionUno();
		assertThat(libroIds(directory.buscar(orgId, CONSULTA_DERECHOS, 5).pasajes())).containsOnly(viejo);

		assertThat(reproceso.encolarSiguiente()).isTrue();
		long nuevo = reemplazoDe(viejo);
		Map<String, Object> fila = jdbc.queryForMap("""
				SELECT n.estado, n.version_procesamiento, n.hash_sha256 = v.hash_sha256 AS mismo_hash,
				       n.storage_key <> v.storage_key AS otra_clave, n.storage_key, n.activo
				  FROM libro n JOIN libro v ON v.id = n.reemplaza_libro_id WHERE n.id = ?""", nuevo);
		assertThat(fila.get("estado")).isEqualTo("PENDIENTE");
		assertThat(fila.get("version_procesamiento")).isEqualTo(2);
		assertThat(((Number) fila.get("mismo_hash")).intValue()).isEqualTo(1);
		assertThat(((Number) fila.get("otra_clave")).intValue()).isEqualTo(1);
		assertThat(almacen.leer(LibroService.ESPACIO, (String) fila.get("storage_key"))).isPresent();
		// Ya hay un reemplazo en curso: no se encola otro.
		assertThat(reproceso.encolarSiguiente()).isFalse();

		// Mientras el nuevo se procesa (justo antes de su cierre), la busqueda sigue en el viejo.
		List<List<PasajeRecuperado>> durante = new ArrayList<>();
		doAnswer(inv -> {
			if ("PROCESANDO".equals(estado(nuevo))) {
				durante.add(directory.buscar(orgId, CONSULTA_DERECHOS, 5).pasajes());
				durante.add(directory.buscar(orgId, "ejercicios de rodilla", 5).pasajes());
			}
			return inv.callRealMethod();
		}).when(version).asegurarFila(anyLong());

		procesarHasta(nuevo);

		assertThat(durante).hasSize(2).allSatisfy(p -> assertThat(libroIds(p)).isNotEmpty().containsOnly(viejo));
		assertThat(estado(nuevo)).isEqualTo("LISTO");
		assertThat(jdbc.queryForList("""
				SELECT id FROM libro WHERE organization_id = ? AND activo = 1""", Long.class, orgId))
				.containsExactly(nuevo);
		assertThat(jdbc.queryForMap("SELECT version_procesamiento, reemplaza_libro_id FROM libro WHERE id = ?", nuevo))
				.containsEntry("version_procesamiento", 2).containsEntry("reemplaza_libro_id", null);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM libro_fragmento
				 WHERE libro_id = ? AND (texto LIKE '%derechos reservados%' OR pagina_desde = 1)""",
				Integer.class, nuevo)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM libro_fragmento WHERE libro_id = ?",
				Integer.class, nuevo)).isPositive();
		assertThat(libroIds(directory.buscar(orgId, "ejercicios de rodilla", 5).pasajes())).containsOnly(nuevo);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE entity_id = ? AND event_type = 'LIBRO_REPROCESADO' AND actor_account_id IS NULL""",
				Integer.class, nuevo)).isEqualTo(1);
	}

	@Test
	void un_reproceso_que_falla_deja_el_viejo_y_no_se_reintenta_mas_de_tres_veces() throws Exception {
		long viejo = libroEnVersionUno();
		doThrow(new DocumentoIlegibleException("El PDF está protegido o dañado"))
				.when(extractor).extraer(any(byte[].class), anyInt());

		Long nuevo = null;
		for (int vuelta = 0; vuelta < 6; vuelta++) {
			if (!reproceso.encolarSiguiente()) {
				break;
			}
			if (nuevo == null) {
				nuevo = reemplazoDe(viejo);
			}
			procesarHasta(nuevo);
		}

		assertThat(nuevo).isNotNull();
		assertThat(jdbc.queryForMap("SELECT estado, intentos FROM libro WHERE id = ?", nuevo))
				.containsEntry("estado", "ERROR").containsEntry("intentos", 3);
		assertThat(jdbc.queryForMap("SELECT activo, estado, version_procesamiento FROM libro WHERE id = ?", viejo))
				.containsEntry("activo", true).containsEntry("estado", "LISTO").containsEntry("version_procesamiento", 1);
		assertThat(reproceso.encolarSiguiente()).isFalse();
		assertThat(libroIds(directory.buscar(orgId, "ejercicios de rodilla", 5).pasajes())).containsOnly(viejo);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event WHERE entity_id = ? AND event_type = 'LIBRO_REPROCESADO'""",
				Integer.class, nuevo)).isEqualTo(3);
	}

	// ------------------------------------------------------------------ soporte

	/** Un libro LISTO con el indice que dejaba la ingesta v1: version 1 y la pagina de derechos adentro. */
	private long libroEnVersionUno() throws IOException {
		soloLosDeEsteTest();
		long id = libros.subir(actor, orgId, new DatosDeLibro("Libro " + UUID.randomUUID(), null, null, null),
				new ArchivoSubido("libro.pdf", pdf(DERECHOS, pagina(2), pagina(3))), true).id();
		procesarHasta(id);
		assertThat(estado(id)).isEqualTo("LISTO");
		assertThat(jdbc.queryForObject("SELECT version_procesamiento FROM libro WHERE id = ?", Integer.class, id))
				.isEqualTo(2);
		jdbc.update("UPDATE libro SET version_procesamiento = 1 WHERE id = ?", id);
		jdbc.update("""
				INSERT INTO libro_fragmento (organization_id, libro_id, orden, pagina_desde, pagina_hasta, texto, sospechoso)
				VALUES (?, ?, 999, 1, 1, ?, 0)""", orgId, id, DERECHOS.replace('\n', ' '));
		// La busqueda cachea los vectores por version de la organizacion: el fragmento agregado a mano
		// tiene que invalidarla.
		jdbc.update("UPDATE biblioteca_version SET version = version + 1 WHERE organization_id = ?", orgId);
		return id;
	}

	/**
	 * La cola y los candidatos son globales y la base se comparte con otras clases de IT: se borran
	 * los PENDIENTE ajenos y los LISTO ajenos pasan a la version vigente, para que el reproceso elija
	 * el libro de este test.
	 */
	private void soloLosDeEsteTest() {
		jdbc.update("""
				DELETE r FROM libro r JOIN libro orig ON orig.id = r.reemplaza_libro_id
				 WHERE r.organization_id <> ? AND (r.estado = 'PENDIENTE' OR orig.estado = 'PENDIENTE')""", orgId);
		jdbc.update("DELETE FROM libro WHERE estado = 'PENDIENTE' AND organization_id <> ?", orgId);
		jdbc.update("UPDATE libro SET version_procesamiento = 2 WHERE organization_id <> ?", orgId);
	}

	private long reemplazoDe(long id) {
		return jdbc.queryForObject("SELECT id FROM libro WHERE reemplaza_libro_id = ?", Long.class, id);
	}

	private static List<Long> libroIds(List<PasajeRecuperado> pasajes) {
		return pasajes.stream().map(PasajeRecuperado::libroId).distinct().toList();
	}

	private void procesarHasta(long id) {
		for (int i = 0; i < 100; i++) {
			if (!procesamiento.procesarSiguiente(Instant.now())) {
				return;
			}
			String e = estado(id);
			if (e == null || "LISTO".equals(e) || "ERROR".equals(e)) {
				return;
			}
		}
	}

	private String estado(long id) {
		return jdbc.query("SELECT estado FROM libro WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, id);
	}

	private static String pagina(int n) {
		StringBuilder sb = new StringBuilder("Capitulo " + n + ". ");
		while (sb.length() < 1300) {
			sb.append(RELLENO).append(' ');
		}
		return sb.toString();
	}

	/** Cada texto es una pagina; los saltos de renglon del texto se respetan y el resto se corta a ~100. */
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
					for (String renglon : texto.split("\n")) {
						StringBuilder linea = new StringBuilder();
						for (String palabra : renglon.split(" ")) {
							if (linea.length() + palabra.length() > 100) {
								cs.showText(linea.toString().strip());
								cs.newLine();
								linea.setLength(0);
							}
							linea.append(palabra).append(' ');
						}
						cs.showText(linea.toString().strip());
						cs.newLine();
					}
					cs.endText();
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}
}
