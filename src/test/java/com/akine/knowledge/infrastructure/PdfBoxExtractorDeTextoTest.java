package com.akine.knowledge.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import com.akine.knowledge.domain.PaginaDeTexto;
import com.akine.knowledge.domain.exception.DocumentoIlegibleException;

class PdfBoxExtractorDeTextoTest {

	private final PdfBoxExtractorDeTexto extractor = new PdfBoxExtractorDeTexto();

	private static byte[] pdf(boolean cifrado, String... textos) throws Exception {
		return pdf(cifrado ? "usuario" : null, textos);
	}

	private static byte[] pdf(String claveDeUsuario, String... textos) throws Exception {
		try (PDDocument doc = new PDDocument()) {
			for (String t : textos) {
				PDPage page = new PDPage();
				doc.addPage(page);
				try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
					cs.beginText();
					cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					cs.newLineAtOffset(50, 700);
					cs.showText(t);
					cs.endText();
				}
			}
			if (claveDeUsuario != null) {
				StandardProtectionPolicy policy = new StandardProtectionPolicy("dueno", claveDeUsuario, new AccessPermission());
				policy.setEncryptionKeyLength(128);
				doc.protect(policy);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			return out.toByteArray();
		}
	}

	@Test
	void ordenaPorPosicionYConservaLosRenglones() throws Exception {
		byte[] bytes;
		try (PDDocument doc = new PDDocument()) {
			PDPage page = new PDPage();
			doc.addPage(page);
			try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
				PDType1Font fuente = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
				// El archivo escribe primero el renglon de abajo.
				for (Object[] r : new Object[][] { { 600f, "Renglon de abajo" }, { 700f, "Renglon de arriba" } }) {
					cs.beginText();
					cs.setFont(fuente, 12);
					cs.newLineAtOffset(50, (float) r[0]);
					cs.showText((String) r[1]);
					cs.endText();
				}
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			doc.save(out);
			bytes = out.toByteArray();
		}

		String texto = extractor.extraer(bytes, 10).get(0).texto();

		assertThat(texto).doesNotContain("\r");
		assertThat(texto.indexOf("Renglon de arriba")).isLessThan(texto.indexOf("Renglon de abajo"));
		assertThat(texto).containsPattern("Renglon de arriba\\n+Renglon de abajo");
	}

	@Test
	void dosPaginasDanDosPaginasDeTexto() throws Exception {
		List<PaginaDeTexto> paginas = extractor.extraer(pdf(false, "Hola uno", "Hola dos"), 10);
		assertThat(paginas).hasSize(2);
		assertThat(paginas.get(0).numero()).isEqualTo(1);
		assertThat(paginas.get(0).texto()).contains("Hola uno");
		assertThat(paginas.get(1).texto()).contains("Hola dos");
	}

	@Test
	void pdfConSoloClaveDeDuenoSeLee() throws Exception {
		List<PaginaDeTexto> paginas = extractor.extraer(pdf("", "Texto libre"), 10);
		assertThat(paginas).hasSize(1);
		assertThat(paginas.get(0).texto()).contains("Texto libre");
	}

	@Test
	void pdfCifradoLanzaDocumentoIlegible() throws Exception {
		byte[] cifrado = pdf(true, "secreto");
		assertThatThrownBy(() -> extractor.extraer(cifrado, 10)).isInstanceOf(DocumentoIlegibleException.class)
				.hasMessage("El PDF está protegido o dañado");
	}

	@Test
	void masPaginasQueElTopeYBytesBasuraLanzan() throws Exception {
		byte[] tres = pdf(false, "a", "b", "c");
		assertThatThrownBy(() -> extractor.extraer(tres, 2)).isInstanceOf(DocumentoIlegibleException.class)
				.hasMessage("El PDF tiene más de 2 páginas");
		assertThatThrownBy(() -> extractor.extraer("no es un pdf".getBytes(), 10))
				.isInstanceOf(DocumentoIlegibleException.class);
	}
}
