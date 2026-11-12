package com.akine.knowledge.infrastructure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import com.akine.knowledge.domain.PaginaDeTexto;
import com.akine.knowledge.domain.exception.DocumentoIlegibleException;
import com.akine.knowledge.domain.port.ExtractorDeTexto;

@Component
public class PdfBoxExtractorDeTexto implements ExtractorDeTexto {

	static final String MOTIVO_PROTEGIDO = "El PDF está protegido o dañado";

	private static String sinRetornos(String texto) {
		return texto.replace("\r\n", "\n").replace('\r', '\n');
	}

	@Override
	public List<PaginaDeTexto> extraer(byte[] pdf, int maxPaginas) {
		// Cache en archivos temporales: acota el heap con PDF de 100 MB. Un PDF con sólo restricciones de
		// dueño (clave de usuario vacía) se abre y se lee; si pide clave, falla con InvalidPasswordException.
		try (PDDocument documento = Loader.loadPDF(pdf, "", null, null, IOUtils.createTempFileOnlyStreamCache())) {
			int total = documento.getNumberOfPages();
			if (total > maxPaginas) {
				throw new DocumentoIlegibleException("El PDF tiene más de " + maxPaginas + " páginas");
			}
			// Por posicion: las celdas de una tabla salen en el orden en que se leen y no en el del
			// archivo. Renglones con "\n" (no el separador del sistema) y un renglon en blanco al final
			// de cada parrafo: el limpiador y el troceador cortan por ahi.
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			stripper.setLineSeparator("\n");
			stripper.setParagraphEnd("\n");
			List<PaginaDeTexto> paginas = new ArrayList<>(total);
			for (int n = 1; n <= total; n++) {
				stripper.setStartPage(n);
				stripper.setEndPage(n);
				// El fin de pagina y otros separadores de PDFBox usan el del sistema (CR LF en Windows).
				paginas.add(new PaginaDeTexto(n, sinRetornos(stripper.getText(documento))));
			}
			return paginas;
		}
		catch (InvalidPasswordException e) {
			throw new DocumentoIlegibleException(MOTIVO_PROTEGIDO);
		}
		catch (IOException | RuntimeException e) {
			if (e instanceof DocumentoIlegibleException d) {
				throw d;
			}
			throw new DocumentoIlegibleException(MOTIVO_PROTEGIDO);
		}
	}
}
