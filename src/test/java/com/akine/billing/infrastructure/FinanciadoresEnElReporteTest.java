package com.akine.billing.infrastructure;

import com.akine.reporting.spi.AdvertenciaDeReporte;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * El aporte de financiadores al reporte (M21 y M23, RF-M23-005).
 *
 * <h2>Que decide la correctitud de este aporte</h2>
 *
 * <p>Tres cosas, y ninguna es "que los numeros sumen".
 *
 * <p><b>Primera: que un periodo sin filas de un CERO explicito.</b> Las cinco columnas del resumen
 * de lotes y el total de pagos salen de un {@code SUM} de SQL, y un {@code SUM} sin filas devuelve
 * {@code null}. Un {@code null} que llegue al tablero se muestra vacio, y el operador lee "no se
 * cargo" donde el dato dice "no hubo". Son dos conclusiones opuestas sobre la misma pantalla.
 *
 * <p><b>Segunda: que prestado y presentado NO se sumen ni se resten.</b> Salen de dos modulos
 * distintos —M18 la deuda a nombre del financiador, M21 el lote— y lo prestado que todavia no se
 * presento es una brecha legitima: el lote se arma despues. Compensarlas en el backend borraria
 * justo el numero que el centro quiere mirar.
 *
 * <p><b>Tercera: que la seccion avise cuando su cero es de construccion.</b> Hoy no existe ninguna
 * obligacion con {@code responsable = FINANCIADOR} y nada la produce, asi que en un despliegue real
 * esta seccion devuelve ceros. Sin la advertencia {@code sin-devengado-de-financiador} el tablero
 * miente por omision; con ella, dice que el cableado falta.
 *
 * <p>Nota: esta seccion no publica ningun porcentaje ni ratio —los siete indicadores son
 * {@code DINERO}—, asi que no hay caso de denominador cero que probar. Es coherente con la regla de
 * que el reporte no emite totales que mezclen conceptos.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FinanciadoresEnElReporteTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;

	private static final long OSDE = 11L;
	private static final long SWISS = 22L;

	@Mock private ObligacionRepository obligaciones;
	@Mock private PresentacionRepository presentaciones;
	@Mock private FinanciadorPagoRepository pagos;

	private FinanciadoresEnElReporte contributor;

	@BeforeEach
	void setUp() {
		contributor = new FinanciadoresEnElReporte(obligaciones, presentaciones, pagos);
	}

	@Test
	@DisplayName("Declara a qué reporte aporta, cómo se titula y con qué permiso se lee")
	void se_declara_entero() {
		// El permiso es lo que impide que la cuenta corriente de financiadores viaje a cualquiera
		// que pueda abrir un reporte. La seccion y la clave del reporte viajan al cliente: si
		// alguien las renombra, la pantalla deja de encontrar el bloque y no falla ningun compilador.
		assertThat(contributor.reportes()).containsExactly(ReporteCode.FINANCIADORES);
		assertThat(contributor.seccion()).isEqualTo("financiadores");
		assertThat(contributor.titulo()).isEqualTo("Financiadores");
		assertThat(contributor.permisoRequerido()).isEqualTo("cobro:register");
	}

	@Test
	@DisplayName("Un periodo sin lotes ni pagos devuelve CEROS, nunca nulls")
	void periodo_vacio_es_cero() {
		// Los seis SUM del resumen y el del pago devuelven null sin filas. Si ese null llegara al
		// indicador, el constructor de IndicadorDeReporte lo rechazaria —y el reporte entero
		// fallaria con un 500— o, peor, se mostraria vacio y se leeria como "no se cargo".
		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(aporte.indicadores())
				.hasSize(7)
				.allSatisfy(indicador -> assertThat(indicador.valor()).isNotNull());
		assertThat(valorDe(aporte, "prestado")).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(valorDe(aporte, "presentado")).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(valorDe(aporte, "facturado")).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(valorDe(aporte, "debitado")).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(valorDe(aporte, "cobrado-de-lotes-del-periodo"))
				.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(valorDe(aporte, "cobrado-de-financiadores")).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(valorDe(aporte, "pendiente")).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(aporte.filas()).isEmpty();
	}

	@Test
	@DisplayName("Un SUM que vuelve null en una celda del lote se lee como cero, no rompe la fila")
	void null_en_una_celda_no_rompe_la_fila() {
		// Un lote PRESENTADA sin debito y sin cobro deja esas columnas en null. Si no se
		// normalizaran, el toPlainString() de la fila de detalle tiraria NullPointerException y el
		// reporte entero —no solo esa celda— devolveria 500.
		given(presentaciones.resumirPorFinanciadorEnElReporte(
				anyLong(), anyLong(), any(), any(), anyInt()))
				.willReturn(List.<Object[]>of(
						new Object[] {OSDE, new BigDecimal("90000.00"), null, null, null, null}));

		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(valorDe(aporte, "presentado")).isEqualByComparingTo(new BigDecimal("90000.00"));
		assertThat(valorDe(aporte, "facturado")).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(valorDe(aporte, "debitado")).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(valorDe(aporte, "pendiente")).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(aporte.filas()).singleElement()
				.extracting(FilaDeReporte::celdas)
				.isEqualTo(List.of("11", "0", "90000.00", "0", "0", "0", "0"));
	}

	@Test
	@DisplayName("Lo prestado se agrega por financiador y el total es la suma de los financiadores")
	void el_prestado_se_agrega_por_financiador() {
		// Es el unico indicador que sale de M18: la deuda devengada a nombre del financiador. El
		// total tiene que ser la suma de las filas y no el primer valor que llegue, porque un
		// centro con dos obras sociales tendria la mitad del numero sin que nada avise.
		given(obligaciones.sumarPrestadoPorFinanciadorEnElReporte(
				anyLong(), anyLong(), any(), any(), anyInt()))
				.willReturn(List.<Object[]>of(
						new Object[] {OSDE, new BigDecimal("120000.00")},
						new Object[] {SWISS, new BigDecimal("30000.50")}));

		assertThat(valorDe(contributor.aportar(consulta()), "prestado"))
				.isEqualByComparingTo(new BigDecimal("150000.50"));
	}

	@Test
	@DisplayName("Una obligación sin financiador o sin importe se descarta del prestado")
	void el_prestado_descarta_filas_incompletas() {
		// La columna financiador_id es nullable: las obligaciones a nombre del paciente —que hoy
		// son TODAS— la tienen en null. Sumarlas aca le atribuiria a los financiadores la deuda de
		// los pacientes, que es el error mas caro posible en este tablero.
		given(obligaciones.sumarPrestadoPorFinanciadorEnElReporte(
				anyLong(), anyLong(), any(), any(), anyInt()))
				.willReturn(List.<Object[]>of(
						new Object[] {null, new BigDecimal("999999.00")},
						new Object[] {SWISS, null},
						new Object[] {OSDE, new BigDecimal("1000.00")}));

		assertThat(valorDe(contributor.aportar(consulta()), "prestado"))
				.isEqualByComparingTo(new BigDecimal("1000.00"));
	}

	@Test
	@DisplayName("Los cinco conceptos del lote se agregan cada uno por su cuenta")
	void los_conceptos_del_lote_se_agregan_por_separado() {
		// Presentado, facturado, debitado, cobrado y pendiente son cinco columnas distintas del
		// mismo lote y ninguna se deduce de las otras: un debito del financiador no es un cobro
		// menos, y el pendiente es el saldo que la tabla ya lleva, no una resta que el reporte haga.
		given(presentaciones.resumirPorFinanciadorEnElReporte(
				anyLong(), anyLong(), any(), any(), anyInt()))
				.willReturn(List.<Object[]>of(
						new Object[] {OSDE, bd("100000"), bd("80000"), bd("5000"), bd("60000"),
								bd("15000")},
						new Object[] {SWISS, bd("40000"), bd("40000"), bd("1000"), bd("20000"),
								bd("19000")}));

		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(valorDe(aporte, "presentado")).isEqualByComparingTo(bd("140000"));
		assertThat(valorDe(aporte, "facturado")).isEqualByComparingTo(bd("120000"));
		assertThat(valorDe(aporte, "debitado")).isEqualByComparingTo(bd("6000"));
		assertThat(valorDe(aporte, "cobrado-de-lotes-del-periodo")).isEqualByComparingTo(bd("80000"));
		assertThat(valorDe(aporte, "pendiente")).isEqualByComparingTo(bd("34000"));
	}

	@Test
	@DisplayName("PRESTADO Y PRESENTADO NO SE COMPENSAN: la brecha se muestra tal cual")
	void prestado_y_presentado_no_se_compensan() {
		// Lo prestado que todavia no se presento es legitimo —el lote se arma despues del mes— y no
		// un error a corregir. Si el backend restara uno del otro, el centro perderia la unica
		// forma de ver cuanta prestacion quedo sin reclamar al financiador.
		given(obligaciones.sumarPrestadoPorFinanciadorEnElReporte(
				anyLong(), anyLong(), any(), any(), anyInt()))
				.willReturn(List.<Object[]>of(new Object[] {OSDE, bd("200000")}));
		given(presentaciones.resumirPorFinanciadorEnElReporte(
				anyLong(), anyLong(), any(), any(), anyInt()))
				.willReturn(List.<Object[]>of(
						new Object[] {OSDE, bd("50000"), bd("0"), bd("0"), bd("0"), bd("50000")}));

		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(valorDe(aporte, "prestado")).isEqualByComparingTo(bd("200000"));
		assertThat(valorDe(aporte, "presentado")).isEqualByComparingTo(bd("50000"));
		// Y la fila de detalle los pone lado a lado, sin ninguna celda que los funda.
		assertThat(aporte.filas()).singleElement()
				.extracting(FilaDeReporte::celdas)
				.isEqualTo(List.of("11", "200000", "50000", "0", "0", "0", "50000"));
	}

	@Test
	@DisplayName("El financiador que tiene lote pero no prestado muestra cero, no vacío")
	void sin_prestado_la_celda_es_cero() {
		// Es el caso real de hoy: el lote existe pero el devengado nunca se recableo, asi que el
		// mapa de prestado no tiene esa clave. Una celda vacia en el CSV se interpreta como dato
		// faltante; un cero dice "no hubo deuda devengada a este financiador".
		given(presentaciones.resumirPorFinanciadorEnElReporte(
				anyLong(), anyLong(), any(), any(), anyInt()))
				.willReturn(List.<Object[]>of(
						new Object[] {SWISS, bd("10000"), bd("0"), bd("0"), bd("0"), bd("10000")}));

		assertThat(contributor.aportar(consulta()).filas()).singleElement()
				.extracting(celdas -> celdas.celdas().get(1))
				.isEqualTo("0");
	}

	@Test
	@DisplayName("Cada fila de detalle trae exactamente una celda por columna declarada")
	void la_fila_calza_con_las_columnas() {
		// El CSV de RF-M23-006 escribe las celdas en el orden de las columnas. Una fila con una
		// celda de mas o de menos no falla: corre los importes una posicion y el export publica
		// facturado donde dice debitado.
		given(presentaciones.resumirPorFinanciadorEnElReporte(
				anyLong(), anyLong(), any(), any(), anyInt()))
				.willReturn(List.<Object[]>of(
						new Object[] {OSDE, bd("1"), bd("2"), bd("3"), bd("4"), bd("5")}));

		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(aporte.columnas()).containsExactly(
				"Financiador", "Prestado", "Presentado", "Facturado", "Debitado", "Cobrado",
				"Pendiente");
		assertThat(aporte.filas()).allSatisfy(fila ->
				assertThat(fila.celdas()).hasSameSizeAs(aporte.columnas()));
	}

	@Test
	@DisplayName("Los dos números de cobro son indicadores distintos con cortes distintos")
	void los_dos_cobros_no_se_funden() {
		// Uno dice cuanto se cobro de los lotes que TOCAN el periodo; el otro cuanto entro DENTRO
		// del periodo, por fecha_pago. Fundirlos haria que el reporte cambiara de significado segun
		// que lote estuviera abierto, y que nadie pudiera conciliar contra el extracto del banco.
		given(presentaciones.resumirPorFinanciadorEnElReporte(
				anyLong(), anyLong(), any(), any(), anyInt()))
				.willReturn(List.<Object[]>of(
						new Object[] {OSDE, bd("100000"), bd("100000"), bd("0"), bd("70000"),
								bd("30000")}));
		given(pagos.sumarPagadoEnElReporte(anyLong(), anyLong(), any(), any()))
				.willReturn(bd("45000"));

		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(valorDe(aporte, "cobrado-de-lotes-del-periodo")).isEqualByComparingTo(bd("70000"));
		assertThat(valorDe(aporte, "cobrado-de-financiadores")).isEqualByComparingTo(bd("45000"));
		assertThat(criterioDe(aporte, "cobrado-de-financiadores")).contains("fecha_pago");
		assertThat(criterioDe(aporte, "cobrado-de-lotes-del-periodo")).contains("solapa");
	}

	@Test
	@DisplayName("Con todo en cero la sección ADVIERTE que el cero es por construcción")
	void el_cero_viene_con_advertencia() {
		// Hoy ninguna obligacion se devenga a nombre de un financiador, asi que en produccion esta
		// seccion da ceros. Sin la advertencia el operador lee "el mes no tuvo actividad con
		// financiadores" donde el dato dice "esto todavia no esta cableado", y decide sobre un
		// vacio que no es un vacio.
		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(aporte.advertencias()).singleElement()
				.satisfies(advertencia -> {
					assertThat(advertencia.seccion()).isEqualTo("financiadores");
					assertThat(advertencia.codigo()).isEqualTo("sin-devengado-de-financiador");
					assertThat(advertencia.detalle()).isNotBlank();
				});
	}

	@Test
	@DisplayName("Con prestado distinto de cero la advertencia desaparece")
	void con_prestado_no_hay_advertencia() {
		// El dia que el devengado se recablee la advertencia tiene que apagarse sola, sin que nadie
		// toque esta clase: si quedara pegada, el tablero desacreditaria numeros que ya son ciertos.
		given(obligaciones.sumarPrestadoPorFinanciadorEnElReporte(
				anyLong(), anyLong(), any(), any(), anyInt()))
				.willReturn(List.<Object[]>of(new Object[] {OSDE, bd("1")}));

		assertThat(contributor.aportar(consulta()).advertencias()).isEmpty();
	}

	@Test
	@DisplayName("Con presentado distinto de cero tampoco se advierte, aunque no haya prestado")
	void con_presentado_no_hay_advertencia() {
		// Alcanza con que UNA de las dos puntas tenga actividad: si hay lotes presentados, la
		// cadena esta viva y el cero del prestado ya no es el cero de construccion que la
		// advertencia describe.
		given(presentaciones.resumirPorFinanciadorEnElReporte(
				anyLong(), anyLong(), any(), any(), anyInt()))
				.willReturn(List.<Object[]>of(
						new Object[] {OSDE, bd("1"), bd("0"), bd("0"), bd("0"), bd("1")}));

		assertThat(contributor.aportar(consulta()).advertencias())
				.extracting(AdvertenciaDeReporte::codigo)
				.doesNotContain("sin-devengado-de-financiador");
	}

	@Test
	@DisplayName("Cada indicador publica su fuente, su criterio de fecha y su moneda")
	void cada_numero_dice_de_donde_sale() {
		// Siete numeros parecidos en la misma pantalla y tres tablas fuente distintas. Sin la
		// fuente al lado, el primero que vea dos de ellos va a sumarlos: prestado mas presentado
		// cuenta la misma prestacion dos veces.
		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(aporte.indicadores()).allSatisfy(indicador -> {
			assertThat(indicador.fuente()).isNotBlank();
			assertThat(indicador.criterioDeFecha()).isNotBlank();
			assertThat(indicador.tipo()).isEqualTo(IndicadorDeReporte.Tipo.DINERO);
			assertThat(indicador.moneda()).isEqualTo("ARS");
		});
		// Tres fuentes distintas y cada una lo dice: M18 la deuda, M21 el lote, M21 el pago.
		assertThat(fuenteDe(aporte, "prestado")).contains("M18");
		assertThat(fuenteDe(aporte, "presentado")).contains("presentacion");
		assertThat(fuenteDe(aporte, "cobrado-de-financiadores")).contains("financiador_pago");
	}

	@Test
	@DisplayName("El pendiente declara que es el saldo de hoy y no el de la fecha de corte")
	void el_pendiente_no_se_reconstruye() {
		// El saldo del lote es el actual, no el que habia al cierre del periodo. Quien compare este
		// numero contra un reporte viejo va a ver diferencias legitimas, y el criterio es lo unico
		// que se las explica.
		assertThat(criterioDe(contributor.aportar(consulta()), "pendiente"))
				.contains("NO reconstruido");
	}

	@Test
	@DisplayName("El prestado se recorta por instante proyectado a la zona de la sede")
	void el_prestado_corta_por_instante() {
		// Es la regla que ya costo caro en la caja: una obligacion devengada 21:30 en Ushuaia,
		// convertida con la zona del servidor, cae en el dia siguiente. El reporte no falla, da
		// otro numero, y por eso el criterio nombra la zona de la sede.
		assertThat(criterioDe(contributor.aportar(consulta()), "prestado"))
				.contains("zona de la sede");
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

	private static BigDecimal bd(String valor) {
		return new BigDecimal(valor);
	}

	private static BigDecimal valorDe(AporteDeReporte aporte, String clave) {
		return indicador(aporte, clave).valor();
	}

	private static String criterioDe(AporteDeReporte aporte, String clave) {
		return indicador(aporte, clave).criterioDeFecha();
	}

	private static String fuenteDe(AporteDeReporte aporte, String clave) {
		return indicador(aporte, clave).fuente();
	}

	private static IndicadorDeReporte indicador(AporteDeReporte aporte, String clave) {
		return aporte.indicadores().stream()
				.filter(candidato -> candidato.clave().equals(clave))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No hay indicador " + clave));
	}
}
