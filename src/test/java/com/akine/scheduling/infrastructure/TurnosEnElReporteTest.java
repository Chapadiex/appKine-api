package com.akine.scheduling.infrastructure;

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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * El aporte de M12 al reporte operativo (AKINE-07.06).
 *
 * <h2>La cuenta que decide todo: el ausentismo</h2>
 *
 * <p>La tasa se calcula <b>sobre los turnos que NO fueron cancelados</b>, y no sobre el total. La
 * diferencia no es cosmetica: un centro que cancela medio dia por un feriado veria su ausentismo
 * desplomarse —porque el denominador crece con turnos que nadie podia venir a cumplir— y el
 * indicador dejaria de medir lo unico que existe para medir, que es cuanta gente falto a un turno
 * que seguia en pie.
 *
 * <p>Y con base cero devuelve cero en vez de dividir: un dia sin turnos no tiene ausentismo
 * infinito, no tiene ausentismo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TurnosEnElReporteTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;

	@Mock private TurnoRepository turnos;

	private TurnosEnElReporte contributor;

	@BeforeEach
	void setUp() {
		contributor = new TurnosEnElReporte(turnos);
		given(turnos.contarPorDiaYEstadoEnElReporte(
				anyLong(), anyLong(), any(), any(), anyString(), anyInt(), anyBoolean(), any()))
				.willReturn(List.of());
		given(turnos.contarReprogramadosEnElReporte(anyLong(), anyLong(), any(), any(), anyBoolean(), any()))
				.willReturn(0L);
	}

	@Test
	@DisplayName("Declara a que reportes aporta y con que permiso se lee")
	void se_declara_entero() {
		// El permiso viaja con el aporte y no se asume: un contribuyente que no lo declarara
		// filtraria su seccion a cualquiera que pueda abrir el reporte.
		assertThat(contributor.reportes())
				.containsExactlyInAnyOrder(ReporteCode.TURNOS, ReporteCode.OPERATIVO);
		assertThat(contributor.permisoRequerido()).isEqualTo("turno:read");
		assertThat(contributor.seccion()).isEqualTo("turnos");
	}

	@Test
	@DisplayName("Un periodo sin turnos no da error ni ausentismo infinito: da cero")
	void periodo_vacio() {
		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(aporte.filas()).isEmpty();
		assertThat(valorDe(aporte, "turnos-totales")).isEqualByComparingTo(new BigDecimal("0"));
		assertThat(valorDe(aporte, "tasa-ausentismo")).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	@DisplayName("Cuenta por estado y totaliza: cada fila del dia suma al indicador que le toca")
	void cuenta_por_estado() {
		given(turnos.contarPorDiaYEstadoEnElReporte(
				anyLong(), anyLong(), any(), any(), anyString(), anyInt(), anyBoolean(), any()))
				.willReturn(List.<Object[]>of(
						fila("2026-09-01", "RESERVADO", 4L),
						fila("2026-09-01", "CONFIRMADO", 3L),
						fila("2026-09-02", "AUSENTE", 2L),
						fila("2026-09-02", "CANCELADO", 1L)));

		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(valorDe(aporte, "turnos-totales")).isEqualByComparingTo(new BigDecimal("10"));
		assertThat(valorDe(aporte, "turnos-reservados")).isEqualByComparingTo(new BigDecimal("4"));
		assertThat(valorDe(aporte, "turnos-confirmados")).isEqualByComparingTo(new BigDecimal("3"));
		assertThat(valorDe(aporte, "turnos-ausentes")).isEqualByComparingTo(new BigDecimal("2"));
		assertThat(valorDe(aporte, "turnos-cancelados")).isEqualByComparingTo(new BigDecimal("1"));
		assertThat(aporte.filas()).hasSize(4);
	}

	@Test
	@DisplayName("EL AUSENTISMO EXCLUYE LOS CANCELADOS DEL DENOMINADOR")
	void el_ausentismo_no_cuenta_cancelados() {
		// Diez turnos, cinco cancelados, uno ausente. Sobre el total daria 10 %; sobre los que
		// seguian en pie da 20 %, que es la cuenta que mide lo que el indicador dice medir.
		given(turnos.contarPorDiaYEstadoEnElReporte(
				anyLong(), anyLong(), any(), any(), anyString(), anyInt(), anyBoolean(), any()))
				.willReturn(List.<Object[]>of(
						fila("2026-09-01", "RESERVADO", 4L),
						fila("2026-09-01", "CANCELADO", 5L),
						fila("2026-09-01", "AUSENTE", 1L)));

		assertThat(valorDe(contributor.aportar(consulta()), "tasa-ausentismo"))
				.isEqualByComparingTo(new BigDecimal("20.00"));
	}

	@Test
	@DisplayName("Un periodo entero cancelado no divide por cero: devuelve cero")
	void todo_cancelado() {
		given(turnos.contarPorDiaYEstadoEnElReporte(
				anyLong(), anyLong(), any(), any(), anyString(), anyInt(), anyBoolean(), any()))
				.willReturn(List.<Object[]>of(fila("2026-09-01", "CANCELADO", 3L)));

		assertThat(valorDe(contributor.aportar(consulta()), "tasa-ausentismo"))
				.isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	@DisplayName("Un estado que el reporte no conoce suma al total pero no rompe el desglose")
	void estado_desconocido() {
		// El dia que una etapa agregue un estado nuevo, el reporte tiene que seguir cuadrando en
		// vez de fallar: la fila se muestra igual y el total la cuenta.
		given(turnos.contarPorDiaYEstadoEnElReporte(
				anyLong(), anyLong(), any(), any(), anyString(), anyInt(), anyBoolean(), any()))
				.willReturn(List.<Object[]>of(fila("2026-09-01", "LLEGO_TARDE", 2L)));

		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(valorDe(aporte, "turnos-totales")).isEqualByComparingTo(new BigDecimal("2"));
		assertThat(aporte.filas()).hasSize(1);
	}

	@Test
	@DisplayName("Los reprogramados salen de su propia consulta, no del desglose por estado")
	void reprogramados_aparte() {
		// Reprogramar no es un estado: un turno movido sigue RESERVADO. La unica forma de contarlos
		// es mirar `reprogramado_en`, y por eso es una consulta propia.
		given(turnos.contarReprogramadosEnElReporte(anyLong(), anyLong(), any(), any(), anyBoolean(), any()))
				.willReturn(7L);

		assertThat(valorDe(contributor.aportar(consulta()), "turnos-reprogramados"))
				.isEqualByComparingTo(new BigDecimal("7"));
	}

	@Test
	@DisplayName("Cada indicador declara su fuente y su criterio: el numero no viaja solo")
	void los_indicadores_se_explican() {
		// Un tablero sin criterio obliga a abrir el codigo para saber si "turnos" incluye los
		// cancelados.
		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(aporte.indicadores()).allSatisfy(indicador -> {
			assertThat(indicador.fuente()).isNotBlank();
			assertThat(indicador.criterioDeFecha()).isNotBlank();
		});
	}

	@Test
	@DisplayName("Recortada a la actividad propia, las dos consultas reciben el recorte (G-1)")
	void la_actividad_propia_viaja_a_las_dos_consultas() {
		// Si una sola de las dos consultas se olvidara del recorte, el profesional veria sus turnos
		// por estado y los reprogramados de todo el equipo, y nada fallaria.
		assertThat(contributor.filtraPorActividadPropia()).isTrue();
		ConsultaDeReporte base = consulta();
		ConsultaDeReporte recortada = new ConsultaDeReporte(ORG_ID, CONSULTORIO_ID,
				base.desde(), base.hasta(), base.zona(), base.desdeInstante(),
				base.hastaInstante(), 500, java.util.Set.of(77L));

		contributor.aportar(recortada);

		org.mockito.Mockito.verify(turnos).contarPorDiaYEstadoEnElReporte(
				anyLong(), anyLong(), any(), any(), anyString(), anyInt(),
				org.mockito.ArgumentMatchers.eq(true),
				org.mockito.ArgumentMatchers.eq(java.util.Set.of(77L)));
		org.mockito.Mockito.verify(turnos).contarReprogramadosEnElReporte(
				anyLong(), anyLong(), any(), any(),
				org.mockito.ArgumentMatchers.eq(true),
				org.mockito.ArgumentMatchers.eq(java.util.Set.of(77L)));
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static ConsultaDeReporte consulta() {
		LocalDate desde = LocalDate.of(2026, 9, 1);
		LocalDate hasta = LocalDate.of(2026, 9, 30);
		ZoneId zona = ZoneId.of("America/Argentina/Cordoba");
		return new ConsultaDeReporte(ORG_ID, CONSULTORIO_ID, desde, hasta, zona,
				desde.atStartOfDay(zona).toInstant(), hasta.plusDays(1).atStartOfDay(zona)
				.toInstant(), 500);
	}

	private static Object[] fila(String dia, String estado, long cantidad) {
		return new Object[]{dia, estado, cantidad};
	}

	private static BigDecimal valorDe(AporteDeReporte aporte, String clave) {
		return aporte.indicadores().stream()
				.filter(indicador -> indicador.clave().equals(clave))
				.map(IndicadorDeReporte::valor)
				.findFirst()
				.orElseThrow(() -> new AssertionError("No hay indicador " + clave));
	}
}
