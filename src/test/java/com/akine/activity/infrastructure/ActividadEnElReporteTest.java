package com.akine.activity.infrastructure;

import com.akine.activity.application.CapacidadDeClase;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.resource.spi.EspacioDirectory;
import com.akine.resource.spi.EspacioSnapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Las formulas de las secciones de {@code activity} en los reportes (AKINE-09.01), sin motor.
 *
 * <p>Lo que se prueba aca es lo que un IT no aisla bien: la capacidad efectiva con los tres
 * limites, que las canceladas no ofrezcan ni ocupen, que el tope de filas no recorte los totales,
 * que la oferta se pida una vez por consulta y no una por clase, que "hoy" sea el de la sede y no
 * el de UTC, y el signo de cada movimiento del ledger. El escenario completo contra MySQL es
 * {@code ActividadEnElReporteIT}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ActividadEnElReporteTest {

	private static final long ORG = 1L;
	private static final long SEDE = 7L;
	private static final ZoneId CORDOBA = ZoneId.of("America/Argentina/Cordoba");

	@Mock private ActividadEnElReporteRepository consultas;
	@Mock private OfertaDirectory ofertas;
	@Mock private EspacioDirectory espacios;

	@Test
	@DisplayName("Clases: capacidad efectiva min(clase, oferta, espacio), canceladas fuera de la ocupacion, totales sin tope de filas")
	void clases() {
		OfertaSnapshot oferta = mock(OfertaSnapshot.class);
		given(oferta.capacidad()).willReturn(10);
		given(oferta.nombreComercial()).willReturn("Pilates");
		given(ofertas.find(ORG, SEDE, 100L)).willReturn(Optional.of(oferta));
		EspacioSnapshot sala = mock(EspacioSnapshot.class);
		given(sala.capacidad()).willReturn(6);
		given(sala.name()).willReturn("Sala Pilates");
		given(espacios.findTodos(eq(ORG), any(), any())).willReturn(Map.of(50L, sala));

		List<Object[]> filas = new ArrayList<>();
		// Propia 12, oferta 10, sala 6: efectiva 6.
		filas.add(clase(1, 50L, 12, 6, "REALIZADA", "Grupo avanzado"));
		// Propia 8, oferta 10, sin espacio: efectiva 8.
		filas.add(clase(2, null, 8, 2, "PROGRAMADA", null));
		// Cancelada: cuenta como cancelada, no ofrece ni ocupa.
		filas.add(clase(3, 50L, 12, 0, "CANCELADA", null));
		given(consultas.clasesDelPeriodo(anyLong(), anyLong(), any(), any(), anyString(), anyBoolean(), any()))
				.willReturn(filas);

		ClasesEnElReporte clases = new ClasesEnElReporte(
				consultas, new CapacidadDeClase(ofertas, espacios), ofertas, espacios);
		AporteDeReporte aporte = clases.aportar(consulta(Clock.systemUTC(), 2));

		assertThat(valor(aporte, "clases-programadas")).isEqualByComparingTo("3");
		assertThat(valor(aporte, "clases-dictadas")).isEqualByComparingTo("1");
		assertThat(valor(aporte, "clases-por-dictar")).isEqualByComparingTo("1");
		assertThat(valor(aporte, "clases-canceladas")).isEqualByComparingTo("1");
		assertThat(valor(aporte, "clases-capacidad-ofrecida")).isEqualByComparingTo("14");
		assertThat(valor(aporte, "clases-lugares-ocupados")).isEqualByComparingTo("8");
		assertThat(valor(aporte, "clases-ocupacion")).isEqualByComparingTo("57.14");
		// Tope de 2 filas: el detalle se corta, los totales no.
		assertThat(aporte.filas()).hasSize(2);
		assertThat(aporte.filas().get(0).celdas())
				.containsExactly("2026-10-16", "09:05", "Grupo avanzado", "Sala Pilates", "REALIZADA",
						"6", "6", "100.00");
		assertThat(aporte.filas().get(1).celdas().get(2)).isEqualTo("Pilates");
		// Una lectura de la oferta por consulta, no una por clase.
		verify(ofertas, times(1)).find(ORG, SEDE, 100L);
	}

	@Test
	@DisplayName("Clases: un periodo sin clases da ceros explicitos, no una division por cero")
	void sin_clases() {
		given(consultas.clasesDelPeriodo(anyLong(), anyLong(), any(), any(), anyString(), anyBoolean(), any()))
				.willReturn(List.of());

		AporteDeReporte aporte = new ClasesEnElReporte(
				consultas, new CapacidadDeClase(ofertas, espacios), ofertas, espacios)
				.aportar(consulta(Clock.systemUTC(), 500));

		assertThat(valor(aporte, "clases-ocupacion")).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(aporte.filas()).isEmpty();
	}

	@Test
	@DisplayName("Pases: a las 22:30 de Cordoba (01:30 UTC del dia siguiente) la foto es del dia de la sede")
	void hoy_es_el_de_la_sede() {
		Clock nocheEnCordoba = Clock.fixed(Instant.parse("2026-10-17T01:30:00Z"), ZoneOffset.UTC);
		PasesEnElReporte pases = new PasesEnElReporte(consultas, nocheEnCordoba);

		AporteDeReporte aporte = pases.aportar(consulta(nocheEnCordoba, 500));

		verify(consultas).vigentesPorTipo(ORG, SEDE, LocalDate.of(2026, 10, 16));
		verify(consultas).vencidosPorTipo(ORG, SEDE, LocalDate.of(2026, 10, 1),
				LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 16));
		assertThat(indicador(aporte, "pases-creditos-disponibles").criterioDeFecha())
				.contains("foto al 2026-10-16");
	}

	@Test
	@DisplayName("Pases: consumos y vencimientos se informan en positivo, el ajuste con su signo; un saldo que no reconcilia se advierte")
	void signos_y_conciliacion() {
		given(consultas.movimientosPorProductoYTipo(anyLong(), anyLong(), any(), any())).willReturn(List.of(
				new Object[]{"Pack 8", "PACK", "COMPRA", 2L, 16L},
				new Object[]{"Pack 8", "PACK", "CONSUMO", 3L, -3L},
				new Object[]{"Pack 8", "PACK", "DEVOLUCION", 1L, 1L},
				new Object[]{"Pack 8", "PACK", "AJUSTE", 2L, -5L},
				new Object[]{"Pack 8", "PACK", "VENCIMIENTO", 1L, -4L}));
		given(consultas.vendidosPorTipo(anyLong(), anyLong(), any(), any())).willReturn(List.<Object[]>of(
				new Object[]{"PACK", 2L, 0L}, new Object[]{"ABONO", 3L, 1L}));
		given(consultas.vigentesPorTipo(anyLong(), anyLong(), any())).willReturn(List.<Object[]>of(
				new Object[]{"PACK", 1L, 4L}, new Object[]{"ABONO", 2L, 10L}));
		given(consultas.pasesConSaldoDivergente(ORG, SEDE)).willReturn(2L);

		AporteDeReporte aporte = new PasesEnElReporte(consultas, Clock.systemUTC())
				.aportar(consulta(Clock.systemUTC(), 500));

		assertThat(valor(aporte, "pases-creditos-vendidos")).isEqualByComparingTo("16");
		assertThat(valor(aporte, "pases-creditos-consumidos")).isEqualByComparingTo("3");
		assertThat(valor(aporte, "pases-creditos-devueltos")).isEqualByComparingTo("1");
		assertThat(valor(aporte, "pases-creditos-ajustados")).isEqualByComparingTo("-5");
		assertThat(valor(aporte, "pases-creditos-vencidos")).isEqualByComparingTo("4");
		assertThat(valor(aporte, "pases-packs-vendidos")).isEqualByComparingTo("2");
		assertThat(valor(aporte, "pases-abonos-vendidos")).isEqualByComparingTo("3");
		assertThat(valor(aporte, "pases-abonos-renovados")).isEqualByComparingTo("1");
		assertThat(valor(aporte, "pases-creditos-disponibles")).isEqualByComparingTo("14");
		assertThat(aporte.filas()).hasSize(5);
		assertThat(aporte.advertencias()).singleElement()
				.satisfies(a -> {
					assertThat(a.codigo()).isEqualTo(PasesEnElReporte.ADVERTENCIA_SALDO_NO_RECONCILIA);
					assertThat(a.detalle()).startsWith("2 pases");
				});
		// Ningun indicador de pases es dinero: credito no es dinero (RN-M23-006).
		assertThat(aporte.indicadores()).allSatisfy(i ->
				assertThat(i.tipo()).isEqualTo(IndicadorDeReporte.Tipo.CONTEO));
	}

	// =================================================================================

	private static Object[] clase(long id, Long espacioId, int capacidad, int ocupados, String estado, String titulo) {
		return new Object[]{id, 100L, espacioId, capacidad, ocupados, estado,
				Instant.parse("2026-10-16T12:05:00Z").getEpochSecond(),
				java.sql.Date.valueOf("2026-10-16"), 9, 5, titulo};
	}

	private static ConsultaDeReporte consulta(Clock clock, int limiteFilas) {
		LocalDate desde = LocalDate.of(2026, 10, 1);
		LocalDate hasta = LocalDate.of(2026, 10, 31);
		return new ConsultaDeReporte(ORG, SEDE, desde, hasta, CORDOBA,
				desde.atStartOfDay(CORDOBA).toInstant(), hasta.plusDays(1).atStartOfDay(CORDOBA).toInstant(),
				limiteFilas);
	}

	private static IndicadorDeReporte indicador(AporteDeReporte aporte, String clave) {
		return aporte.indicadores().stream().filter(i -> i.clave().equals(clave)).findFirst().orElseThrow();
	}

	private static BigDecimal valor(AporteDeReporte aporte, String clave) {
		return indicador(aporte, clave).valor();
	}
}
