package com.akine.knowledge.infrastructure;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
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

	@Override
	public List<PaginaDeTexto> extraer(byte[] pdf, int maxPaginas) {
		try (PDDocument documento = Loader.loadPDF(pdf)) {
			if (documento.isEncrypted()) {
				throw new DocumentoIlegibleException(MOTIVO_PROTEGIDO);
			}
			int total = documento.getNumberOfPages();
			if (total > maxPaginas) {
				throw new DocumentoIlegibleException("El PDF tiene más de " + maxPaginas + " páginas");
			}
			PDFTextStripper stripper = new PDFTextStripper();
			List<PaginaDeTexto> paginas = new ArrayList<>(total);
			for (int n = 1; n <= total; n++) {
				stripper.setStartPage(n);
				stripper.setEndPage(n);
				paginas.add(new PaginaDeTexto(n, stripper.getText(documento)));
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
