package com.akine.billing.infrastructure;

import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * El aporte economico al reporte (M18 a M22, AKINE-07.06).
 *
 * <h2>El indicador que justifica la seccion entera</h2>
 *
 * <p><b>La diferencia de conciliacion tiene que dar cero.</b> Es la resta entre lo que M19 dice
 * haber cobrado en efectivo y lo que M20 dice que entro al cajon por cobros. Distinta de cero
 * significa plata cobrada que no entro a ninguna caja —o un movimiento de caja que no corresponde a
 * ningun cobro—, y es la unica forma de que el centro se entere <b>sin contar los billetes</b>.
 *
 * <h2>Tres criterios de fecha distintos en la misma seccion, y es a proposito</h2>
 *
 * <p>Lo devengado y lo cobrado se recortan por <b>instante</b> proyectado a la zona de la sede; la
 * caja, por <b>fecha de negocio</b>, que ya es una fecha local; y la deuda vigente <b>no se recorta
 * por periodo</b>: es el saldo de hoy y no el que habia a la fecha de corte. Mezclarlos daria
 * numeros que no cierran entre si, y por eso cada indicador publica su criterio.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EconomiaEnElReporteTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;

	@Mock private ObligacionRepository obligaciones;
	@Mock private CobroRepository cobros;
	@Mock private MovimientoCajaRepository movimientos;
	@Mock private JornadaCajaRepository jornadas;
	@Mock private EgresoRepository egresos;

	private EconomiaEnElReporte contributor;

	@BeforeEach
	void setUp() {
		contributor = new EconomiaEnElReporte(
				obligaciones, cobros, movimientos, jornadas, egresos);
	}

	@Test
	@DisplayName("Declara a que reportes aporta y con que permiso se lee")
	void se_declara_entero() {
		// Sin el permiso declarado, la seccion economica viajaria a cualquiera que pueda abrir el
		// reporte operativo.
		assertThat(contributor.reportes())
				.containsExactlyInAnyOrder(ReporteCode.ECONOMICO, ReporteCode.OPERATIVO);
		assertThat(contributor.permisoRequerido()).isEqualTo("cobro:register");
		assertThat(contributor.seccion()).isEqualTo("economia");
	}

	@Test
	@DisplayName("Un periodo sin movimiento devuelve CEROS, no nulls")
	void periodo_vacio_es_cero() {
		// Las sumas de SQL devuelven null cuando no hay filas. Un null que llegara al tablero se
		// mostraria como vacio y el centro leeria "no se cargo" donde en realidad dice "no hubo".
		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(aporte.indicadores()).allSatisfy(indicador ->
				assertThat(indicador.valor()).isNotNull());
		assertThat(valorDe(aporte, "devengado")).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(valorDe(aporte, "cobrado")).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	@DisplayName("LA CONCILIACION ES LA RESTA ENTRE LO COBRADO EN EFECTIVO Y LO QUE ENTRO AL CAJON")
	void la_conciliacion_cierra() {
		// Es el indicador que justifica la seccion: distinto de cero significa plata cobrada en
		// efectivo que no entro a ninguna caja, o al reves.
		given(cobros.sumarCobradoPorMedioEnElReporte(
				anyLong(), anyLong(), any(), any(), anyString()))
				.willReturn(new BigDecimal("50000.00"));
		given(movimientos.sumarEfectivoDeCobrosEnElReporte(anyLong(), anyLong(), any(), any()))
				.willReturn(new BigDecimal("50000.00"));

		assertThat(valorDe(contributor.aportar(consulta()), "conciliacion-diferencia"))
				.isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	@DisplayName("Si falta plata en el cajon, la diferencia lo dice en vez de taparlo")
	void la_conciliacion_delata() {
		// Cobrados 50.000 en efectivo pero solo 45.000 llegaron a una caja: faltan 5.000 que nadie
		// asento, y el tablero lo muestra sin que haga falta contar los billetes.
		given(cobros.sumarCobradoPorMedioEnElReporte(
				anyLong(), anyLong(), any(), any(), anyString()))
				.willReturn(new BigDecimal("50000.00"));
		given(movimientos.sumarEfectivoDeCobrosEnElReporte(anyLong(), anyLong(), any(), any()))
				.willReturn(new BigDecimal("45000.00"));

		assertThat(valorDe(contributor.aportar(consulta()), "conciliacion-diferencia"))
				.isEqualByComparingTo(new BigDecimal("5000.00"));
	}

	@Test
	@DisplayName("Lo devengado y lo anulado son indicadores SEPARADOS, no una resta")
	void devengado_y_anulado_van_aparte() {
		// Restarlos en el backend esconderia cuanto se anulo, que es justo lo que un centro quiere
		// mirar cuando el producido no cierra con lo esperado.
		given(obligaciones.sumarDevengadoEnElReporte(anyLong(), anyLong(), any(), any()))
				.willReturn(new BigDecimal("120000.00"));
		given(obligaciones.sumarAnuladoEnElReporte(anyLong(), anyLong(), any(), any()))
				.willReturn(new BigDecimal("8000.00"));

		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(valorDe(aporte, "devengado")).isEqualByComparingTo(new BigDecimal("120000.00"));
		assertThat(valorDe(aporte, "devengado-anulado"))
				.isEqualByComparingTo(new BigDecimal("8000.00"));
	}

	@Test
	@DisplayName("La deuda vigente NO se recorta por periodo: es el saldo de hoy")
	void la_deuda_vigente_es_de_hoy() {
		// Y por eso su criterio lo dice con todas las letras: reconstruir el saldo a una fecha de
		// corte es otra cuenta, y mezclarlas daria un tablero que no cierra consigo mismo.
		given(obligaciones.sumarSaldoVigenteEnElReporte(anyLong(), anyLong()))
				.willReturn(new BigDecimal("33000.00"));

		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(valorDe(aporte, "deuda-vigente")).isEqualByComparingTo(new BigDecimal("33000.00"));
		assertThat(criterioDe(aporte, "deuda-vigente")).contains("NO reconstruido");
	}

	@Test
	@DisplayName("La caja se recorta por FECHA DE NEGOCIO y el resto por instante")
	void los_criterios_no_se_mezclan() {
		// Tres criterios distintos conviven a proposito, y cada indicador publica el suyo: sin eso,
		// nadie puede saber por que lo cobrado de un dia no coincide con lo que entro al cajon.
		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(criterioDe(aporte, "caja-ingresos-efectivo")).contains("fecha_negocio");
		assertThat(criterioDe(aporte, "cobrado")).contains("zona de la sede");
	}

	@Test
	@DisplayName("Cada indicador dice de que modulo y tabla sale")
	void cada_numero_dice_de_donde_sale() {
		// Un tablero economico con doce numeros parecidos y sin fuente es un tablero que nadie
		// puede auditar.
		assertThat(contributor.aportar(consulta()).indicadores()).allSatisfy(indicador -> {
			assertThat(indicador.fuente()).isNotBlank();
			assertThat(indicador.criterioDeFecha()).isNotBlank();
		});
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static ConsultaDeReporte consulta() {
		LocalDate desde = LocalDate.of(2026, 9, 1);
		LocalDate hasta = LocalDate.of(2026, 9, 30);
		ZoneId zona = ZoneId.of("America/Argentina/Cordoba");
		return new ConsultaDeReporte(ORG_ID, CONSULTORIO_ID, desde, hasta, zona,
				desde.atStartOfDay(zona).toInstant(),
				hasta.plusDays(1).atStartOfDay(zona).toInstant(), 500);
	}

	private static BigDecimal valorDe(AporteDeReporte aporte, String clave) {
		return indicador(aporte, clave).valor();
	}

	private static String criterioDe(AporteDeReporte aporte, String clave) {
		return indicador(aporte, clave).criterioDeFecha();
	}

	private static IndicadorDeReporte indicador(AporteDeReporte aporte, String clave) {
		return aporte.indicadores().stream()
				.filter(candidato -> candidato.clave().equals(clave))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No hay indicador " + clave));
	}
}
