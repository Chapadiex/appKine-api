package com.akine.billing.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DP-45: la aritmetica de las tiras de caja y de egresos, que no necesita base. */
class ResumenesDP45Test {

	@Test
	@DisplayName("Caja: efectivo y otros medios por separado; los totales son su suma")
	void resumen_de_jornada() {
		JornadaCajaView.Resumen resumen = JornadaCajaView.Resumen.de(List.of(
				new JornadaCajaView.TotalPorMedio("EFECTIVO", new BigDecimal("7000.00"), true,
						new BigDecimal("10000.00"), new BigDecimal("3000.00"), 4),
				new JornadaCajaView.TotalPorMedio("TRANSFERENCIA", new BigDecimal("5000.00"), false,
						new BigDecimal("5000.00"), BigDecimal.ZERO, 1),
				new JornadaCajaView.TotalPorMedio("TARJETA_DEBITO", new BigDecimal("2000.00"), false,
						new BigDecimal("2500.00"), new BigDecimal("500.00"), 2)));

		assertThat(resumen.efectivoIngresos()).isEqualByComparingTo("10000.00");
		assertThat(resumen.efectivoEgresos()).isEqualByComparingTo("3000.00");
		assertThat(resumen.otrosMediosIngresos()).isEqualByComparingTo("7500.00");
		assertThat(resumen.otrosMediosEgresos()).isEqualByComparingTo("500.00");
		assertThat(resumen.ingresos()).isEqualByComparingTo("17500.00");
		assertThat(resumen.egresos()).isEqualByComparingTo("3500.00");
		assertThat(resumen.cantidadMovimientos()).isEqualTo(7);
	}

	@Test
	@DisplayName("Egresos: registrado = confirmados + pagados, pagado = registrado - saldo; borradores y anulados aparte")
	void resumen_de_egresos() {
		ResumenDeEgresosView vista = ResumenDeEgresosView.de(7L,
				LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), List.<Object[]>of(
						new Object[]{"ARS", "ALQUILER", "PAGADO", 1L, new BigDecimal("50000.00"), BigDecimal.ZERO},
						new Object[]{"ARS", "HONORARIOS_PROFESIONALES", "CONFIRMADO", 2L,
								new BigDecimal("30000.00"), new BigDecimal("12000.00")},
						new Object[]{"ARS", "HONORARIOS_PROFESIONALES", "BORRADOR", 1L,
								new BigDecimal("8000.00"), new BigDecimal("8000.00")},
						new Object[]{"ARS", "INSUMOS", "ANULADO", 1L, new BigDecimal("900.00"),
								new BigDecimal("900.00")}));

		assertThat(vista.porMoneda()).singleElement().satisfies(t -> {
			assertThat(t.registrado()).isEqualByComparingTo("80000.00");
			assertThat(t.pagado()).isEqualByComparingTo("68000.00");
			assertThat(t.pendiente()).isEqualByComparingTo("12000.00");
			assertThat(t.cantidad()).isEqualTo(3);
			assertThat(t.cantidadBorrador()).isEqualTo(1);
			assertThat(t.importeBorrador()).isEqualByComparingTo("8000.00");
			assertThat(t.cantidadAnulados()).isEqualTo(1);
		});
		assertThat(vista.porCategoria()).extracting(ResumenDeEgresosView.PorCategoria::categoria)
				.as("insumos solo tiene un anulado: no es una categoria con egresos")
				.containsExactly("ALQUILER", "HONORARIOS_PROFESIONALES");
	}
}
