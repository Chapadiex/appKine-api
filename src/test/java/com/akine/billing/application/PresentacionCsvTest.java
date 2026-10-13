package com.akine.billing.application;

import com.akine.billing.domain.EstadoItemPresentacion;
import com.akine.billing.domain.EstadoPresentacion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PresentacionCsvTest {

	private final PresentacionCsv csv = new PresentacionCsv();

	@Test
	@DisplayName("una fila por prestacion, con PHI minima y el texto libre neutralizado como formula")
	void serializa_el_detalle() {
		PresentacionItemView debitado = new PresentacionItemView(
				12L, 40L, EstadoItemPresentacion.DEBITADO, new BigDecimal("15000.00"),
				new BigDecimal("15000.00"), "=HYPERLINK(\"http://x\")", Instant.parse("2026-10-02T12:00:00Z"),
				128L, 77L, LocalDate.of(2026, 9, 15), "Sesion \"kinesiologia\", rodilla",
				Instant.parse("2026-10-01T12:00:00Z"), 1L);
		PresentacionView lote = lote(4, List.of(debitado));

		String archivo = csv.aCsv(lote);

		assertThat(archivo)
				.contains("\"Financiador\",\"OSDE\"\r\n")
				.contains("\"Total presentado\",\"100000.00\"\r\n")
				.contains("\"12\",\"40\",\"77\",\"128\",\"2026-09-15\","
						+ "\"Sesion \"\"kinesiologia\"\", rodilla\",\"DEBITADO\",\"15000.00\",\"15000.00\","
						+ "\"'=HYPERLINK(\"\"http://x\"\")\"\r\n");
		assertThat(csv.nombreDeArchivo(lote)).isEqualTo("presentacion-3-4.csv");
	}

	@Test
	@DisplayName("un borrador sin numero se nombra por su id y exporta la cabecera sin items")
	void borrador() {
		PresentacionView lote = lote(null, null);

		assertThat(csv.nombreDeArchivo(lote)).isEqualTo("presentacion-borrador-11.csv");
		assertThat(csv.aCsv(lote)).contains("\"Numero\",\"\"\r\n").endsWith("\"Motivo de debito\"\r\n");
	}

	private static PresentacionView lote(Integer numero, List<PresentacionItemView> items) {
		return new PresentacionView(
				11L, 7L, 3L, "OSDE", numero, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
				"ARS", numero == null ? EstadoPresentacion.BORRADOR : EstadoPresentacion.PRESENTADA,
				new BigDecimal("100000.00"), new BigDecimal("15000.00"), BigDecimal.ZERO,
				new BigDecimal("85000.00"), null, null, null, null, null, null,
				Instant.parse("2026-10-01T12:00:00Z"), 2L, items);
	}
}
