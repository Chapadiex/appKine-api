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
	@DisplayName("cada renglon dice quien fue, su afiliado, la practica y la autorizacion; el texto libre se neutraliza")
	void serializa_el_detalle_legible() {
		PresentacionItemView debitado = renglon(12L, 128L, LocalDate.of(2026, 9, 15),
				EstadoItemPresentacion.DEBITADO, "15000.00", "=HYPERLINK(\"http://x\")")
				.conDatos("Perez, Ana", "DNI 30111222", "AF-123", "Plan 310", "KIN_SESION",
						"Sesion de kinesiologia", "AUT-9");
		PresentacionView lote = lote(4, List.of(debitado));

		String archivo = csv.aCsv(lote);

		assertThat(archivo)
				.contains("\"Financiador\",\"OSDE\"\r\n")
				.contains("\"Total presentado\",\"100000.00\"\r\n")
				.contains("\"Perez, Ana\",\"DNI 30111222\",\"AF-123\",\"Plan 310\",\"2026-09-15\","
						+ "\"KIN_SESION\",\"Sesion de kinesiologia\",\"AUT-9\","
						+ "\"Sesion \"\"kinesiologia\"\", rodilla\",\"DEBITADO\",\"15000.00\",\"15000.00\","
						+ "\"'=HYPERLINK(\"\"http://x\"\")\",\"77\",\"12\"\r\n");
		assertThat(csv.nombreDeArchivo(lote)).isEqualTo("presentacion-3-4.csv");
	}

	@Test
	@DisplayName("el resumen por paciente cuenta sesiones, junta fechas y suma; los anulados no cuentan")
	void resumen_por_paciente() {
		PresentacionItemView ana1 = renglon(1L, 128L, LocalDate.of(2026, 9, 17),
				EstadoItemPresentacion.INCLUIDO, "7000.00", null)
				.conDatos("Perez, Ana", "DNI 1", "AF-1", "Plan 310", null, null, null);
		PresentacionItemView ana2 = renglon(2L, 128L, LocalDate.of(2026, 9, 3),
				EstadoItemPresentacion.INCLUIDO, "7000.00", null)
				.conDatos("Perez, Ana", "DNI 1", "AF-1", "Plan 310", null, null, null);
		PresentacionItemView anulado = renglon(3L, 128L, LocalDate.of(2026, 9, 9),
				EstadoItemPresentacion.ANULADO, "7000.00", null)
				.conDatos("Perez, Ana", "DNI 1", "AF-1", "Plan 310", null, null, null);
		PresentacionItemView beto = renglon(4L, 200L, LocalDate.of(2026, 9, 5),
				EstadoItemPresentacion.INCLUIDO, "5000.00", null)
				.conDatos("Alvarez, Beto", "DNI 2", "AF-2", "Plan 210", null, null, null);

		String archivo = csv.aCsv(lote(4, List.of(ana1, ana2, anulado, beto)));

		assertThat(archivo).contains("\"Resumen por paciente\"\r\n");
		String resumen = archivo.substring(archivo.indexOf("\"Resumen por paciente\""));
		assertThat(resumen)
				.contains("\"Alvarez, Beto\",\"DNI 2\",\"AF-2\",\"Plan 210\",\"1\",\"2026-09-05\",\"5000.00\"\r\n")
				.contains("\"Perez, Ana\",\"DNI 1\",\"AF-1\",\"Plan 310\",\"2\","
						+ "\"2026-09-03 2026-09-17\",\"14000.00\"\r\n");
		assertThat(resumen.indexOf("Alvarez")).isLessThan(resumen.indexOf("Perez"));
	}

	@Test
	@DisplayName("un borrador sin numero se nombra por su id y exporta la cabecera sin renglones ni resumen")
	void borrador() {
		PresentacionView lote = lote(null, null);

		assertThat(csv.nombreDeArchivo(lote)).isEqualTo("presentacion-borrador-11.csv");
		assertThat(csv.aCsv(lote))
				.contains("\"Numero\",\"\"\r\n")
				.endsWith("\"Sesion\",\"Item\"\r\n")
				.doesNotContain("Resumen por paciente");
	}

	private static PresentacionItemView renglon(
			long id, long personaId, LocalDate fecha, EstadoItemPresentacion estado, String importe,
			String motivo) {
		boolean debitado = estado == EstadoItemPresentacion.DEBITADO;
		return new PresentacionItemView(
				id, 40L, estado, new BigDecimal(importe),
				debitado ? new BigDecimal(importe) : BigDecimal.ZERO, motivo,
				debitado ? Instant.parse("2026-10-02T12:00:00Z") : null,
				personaId, 77L, fecha, "Sesion \"kinesiologia\", rodilla",
				Instant.parse("2026-10-01T12:00:00Z"), 1L,
				null, null, null, null, null, null, null);
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
