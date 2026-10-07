package com.akine.encounter.infrastructure;

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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * El aporte de M14 a los reportes (AKINE-07.06, RF-M23-003).
 *
 * <p>Dos cuentas y nada mas: que "sin caso" sea el complemento de "con caso" sobre las CERRADAS
 * —RN-M23-004, las sesiones con y sin caso no caen en el mismo balde— y que la fila del detalle
 * sin caso tenga nombre en vez de un {@code null} que la pantalla pintaria como "null".
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SesionesEnElReporteTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 7L;

	@Mock private SesionRepository sesiones;

	private SesionesEnElReporte contributor;

	@BeforeEach
	void setUp() {
		contributor = new SesionesEnElReporte(sesiones);
		given(sesiones.contarCerradasPorCasoEnElReporte(anyLong(), anyLong(), any(), any(), anyInt(), anyBoolean(), any()))
				.willReturn(List.of());
	}

	@Test
	@DisplayName("Es una seccion clinica: se lee con sesion:register y su consulta queda auditada")
	void se_declara_clinica() {
		// Un contribuyente que se declarara no clinico dejaria sin auditar la lectura de un
		// reporte que cuenta atenciones, contra lo que 04.01 dejo fijado.
		assertThat(contributor.esClinica()).isTrue();
		assertThat(contributor.permisoRequerido()).isEqualTo("sesion:register");
		assertThat(contributor.reportes())
				.containsExactlyInAnyOrder(ReporteCode.CLINICO, ReporteCode.OPERATIVO);
	}

	@Test
	@DisplayName("Sin caso es el complemento de con caso sobre las cerradas, no otra consulta")
	void sin_caso_es_el_complemento() {
		given(sesiones.contarCerradasEnElReporte(anyLong(), anyLong(), any(), any(), anyBoolean(), any())).willReturn(10L);
		given(sesiones.contarCerradasConCasoEnElReporte(anyLong(), anyLong(), any(), any(), anyBoolean(), any()))
				.willReturn(4L);
		given(sesiones.contarCerradasPorAsistenciaEnElReporte(
				anyLong(), anyLong(), any(), any(), eq("PRESENTE"), anyBoolean(), any())).willReturn(8L);
		given(sesiones.contarCerradasPorAsistenciaEnElReporte(
				anyLong(), anyLong(), any(), any(), eq("AUSENTE"), anyBoolean(), any())).willReturn(2L);
		given(sesiones.contarEnBorradorEnElReporte(anyLong(), anyLong(), any(), any(), anyBoolean(), any())).willReturn(3L);

		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(valorDe(aporte, "sesiones-cerradas")).isEqualByComparingTo("10");
		assertThat(valorDe(aporte, "sesiones-con-caso")).isEqualByComparingTo("4");
		assertThat(valorDe(aporte, "sesiones-sin-caso")).isEqualByComparingTo("6");
		assertThat(valorDe(aporte, "asistencia-presente")).isEqualByComparingTo("8");
		assertThat(valorDe(aporte, "asistencia-ausente")).isEqualByComparingTo("2");
		assertThat(valorDe(aporte, "sesiones-en-borrador"))
				.as("las abiertas se cuentan aparte: no son atenciones cerradas")
				.isEqualByComparingTo("3");
	}

	@Test
	@DisplayName("El detalle agrupa por caso, y el grupo sin caso se nombra en vez de decir null")
	void el_detalle_nombra_el_grupo_sin_caso() {
		given(sesiones.contarCerradasPorCasoEnElReporte(
				eq(ORG_ID), eq(CONSULTORIO_ID), any(), any(), eq(50), anyBoolean(), any()))
				.willReturn(List.<Object[]>of(
						new Object[] {null, 5L},
						new Object[] {17L, java.math.BigInteger.valueOf(3)}));

		AporteDeReporte aporte = contributor.aportar(consulta());

		assertThat(aporte.columnas()).containsExactly("Caso", "Sesiones cerradas");
		assertThat(aporte.filas()).extracting(fila -> fila.celdas())
				.containsExactly(List.of("sin caso", "5"), List.of("17", "3"));
	}

	private static ConsultaDeReporte consulta() {
		return new ConsultaDeReporte(ORG_ID, CONSULTORIO_ID,
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
				ZoneId.of("America/Argentina/Cordoba"),
				Instant.parse("2026-09-01T03:00:00Z"), Instant.parse("2026-10-01T03:00:00Z"), 50);
	}

	private static BigDecimal valorDe(AporteDeReporte aporte, String clave) {
		return aporte.indicadores().stream()
				.filter(indicador -> indicador.clave().equals(clave))
				.map(IndicadorDeReporte::valor)
				.findFirst()
				.orElseThrow();
	}
}
