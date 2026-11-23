package com.akine.billing.infrastructure;

import com.akine.assistant.spi.Tarjeta.ImputacionEnTarjeta;
import com.akine.billing.infrastructure.PagoPorElAsistente.Deuda;
import com.akine.billing.infrastructure.PagoPorElAsistente.Imputado;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** El reparto de un pago entre las deudas del paciente (AKINE-asistente-acciones §6.2). */
class PagoPorElAsistenteTest {

	private static final Deuda VIEJA = new Deuda(1L, "Kinesiologia", LocalDate.of(2026, 10, 1),
			new BigDecimal("10000.00"));
	private static final Deuda NUEVA = new Deuda(2L, "Kinesiologia", LocalDate.of(2026, 11, 1),
			new BigDecimal("8000.00"));

	@Test
	void reparteDeLaMasViejaALaMasNuevaHastaAgotarElMonto() {
		Imputado imputado = PagoPorElAsistente.imputar(List.of(VIEJA, NUEVA), new BigDecimal("15000"));

		assertThat(imputado.imputaciones()).extracting(ImputacionEnTarjeta::obligacionId).containsExactly(1L, 2L);
		assertThat(imputado.imputaciones().get(0).importe()).isEqualByComparingTo("10000");
		assertThat(imputado.imputaciones().get(1).importe()).isEqualByComparingTo("5000");
		assertThat(imputado.imputaciones().get(1).saldo()).isEqualByComparingTo("8000");
		assertThat(imputado.anticipo()).isEqualByComparingTo("0");
	}

	@Test
	void loQueSobraDespuesDeSaldarTodoEsAnticipo() {
		Imputado imputado = PagoPorElAsistente.imputar(List.of(VIEJA), new BigDecimal("15000"));

		assertThat(imputado.imputaciones()).hasSize(1);
		assertThat(imputado.imputaciones().get(0).importe()).isEqualByComparingTo("10000");
		assertThat(imputado.anticipo()).isEqualByComparingTo("5000");
	}

	@Test
	void sinDeudasTodoElMontoEsAnticipo() {
		Imputado imputado = PagoPorElAsistente.imputar(List.of(), new BigDecimal("15000"));

		assertThat(imputado.imputaciones()).isEmpty();
		assertThat(imputado.anticipo()).isEqualByComparingTo("15000");
	}

	@Test
	void sinMontoMuestraLasDeudasConImporteCero() {
		Imputado imputado = PagoPorElAsistente.imputar(List.of(VIEJA, NUEVA), null);

		assertThat(imputado.imputaciones()).hasSize(2)
				.allSatisfy(fila -> assertThat(fila.importe()).isEqualByComparingTo("0"));
		assertThat(imputado.anticipo()).isEqualByComparingTo("0");
	}
}
