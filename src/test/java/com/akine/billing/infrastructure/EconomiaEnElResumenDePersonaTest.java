package com.akine.billing.infrastructure;

import com.akine.billing.domain.EstadoObligacion;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.person.spi.AporteDeResumen;
import com.akine.person.spi.ConsultaDeResumen;
import com.akine.person.spi.IndicadorDeResumen;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;

/**
 * Lo que {@code billing} aporta al Paciente 360.
 *
 * <p>Los dos casos que importan: <b>una obligacion anulada no suma</b> —su saldo ya no se debe—
 * y el total es {@code BigDecimal} exacto. Un centavo perdido en un {@code double} es una
 * discusion en el mostrador, no un detalle de presentacion.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EconomiaEnElResumenDePersona")
class EconomiaEnElResumenDePersonaTest {

	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final long PERSONA_ID = 500L;

	@Mock
	private ObligacionRepositoryPort obligaciones;

	@Test
	@DisplayName("declara cobro:register, el mismo con el que se lee la cuenta corriente")
	void declara_su_permiso() {
		assertThat(new EconomiaEnElResumenDePersona(obligaciones).permisoRequerido())
				.isEqualTo("cobro:register");
		assertThat(new EconomiaEnElResumenDePersona(obligaciones).seccion()).isEqualTo("economia");
	}

	@Test
	@DisplayName("suma los saldos con precision decimal y no cuenta las anuladas")
	void la_anulada_no_suma() {
		given(obligaciones.findDeLaPersona(anyLong(), anyLong())).willReturn(List.of(
				obligacion(1L, new BigDecimal("1500.55"), EstadoObligacion.PENDIENTE),
				obligacion(2L, new BigDecimal("3000.45"), EstadoObligacion.PARCIAL),
				obligacion(3L, new BigDecimal("9999.99"), EstadoObligacion.ANULADA),
				obligacion(4L, BigDecimal.ZERO, EstadoObligacion.PAGADA)));

		AporteDeResumen aporte = new EconomiaEnElResumenDePersona(obligaciones)
				.aportar(new ConsultaDeResumen(ORG_ID, SEDE_ID, PERSONA_ID, 10));

		assertThat(indicador(aporte, "deuda-total").importe())
				.isEqualByComparingTo(new BigDecimal("4501.00"));
		assertThat(indicador(aporte, "deuda-total").moneda()).isEqualTo("ARS");
		assertThat(indicador(aporte, "obligaciones-abiertas").cantidad()).isEqualTo(2L);
	}

	@Test
	@DisplayName("la anulada sigue siendo un hito: el historico se mantiene consultable")
	void la_anulada_sigue_siendo_hito() {
		given(obligaciones.findDeLaPersona(anyLong(), anyLong())).willReturn(List.of(
				obligacion(3L, new BigDecimal("9999.99"), EstadoObligacion.ANULADA)));

		AporteDeResumen aporte = new EconomiaEnElResumenDePersona(obligaciones)
				.aportar(new ConsultaDeResumen(ORG_ID, SEDE_ID, PERSONA_ID, 10));

		assertThat(aporte.hitos()).singleElement()
				.satisfies(hito -> assertThat(hito.estado()).isEqualTo("ANULADA"));
		assertThat(indicador(aporte, "deuda-total").importe()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	@DisplayName("sin obligaciones el aporte trae cero, no una seccion ausente")
	void sin_deuda_hay_aporte() {
		given(obligaciones.findDeLaPersona(anyLong(), anyLong())).willReturn(List.of());

		AporteDeResumen aporte = new EconomiaEnElResumenDePersona(obligaciones)
				.aportar(new ConsultaDeResumen(ORG_ID, SEDE_ID, PERSONA_ID, 10));

		assertThat(indicador(aporte, "deuda-total").importe()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(aporte.hitos()).isEmpty();
	}

	@Test
	@DisplayName("los hitos se recortan al limite pedido")
	void recorta_los_hitos() {
		given(obligaciones.findDeLaPersona(anyLong(), anyLong())).willReturn(List.of(
				obligacion(1L, BigDecimal.TEN, EstadoObligacion.PENDIENTE),
				obligacion(2L, BigDecimal.TEN, EstadoObligacion.PENDIENTE),
				obligacion(3L, BigDecimal.TEN, EstadoObligacion.PENDIENTE)));

		AporteDeResumen aporte = new EconomiaEnElResumenDePersona(obligaciones)
				.aportar(new ConsultaDeResumen(ORG_ID, SEDE_ID, PERSONA_ID, 2));

		assertThat(aporte.hitos()).hasSize(2);
		assertThat(indicador(aporte, "obligaciones-abiertas").cantidad()).isEqualTo(3L);
	}

	private static IndicadorDeResumen indicador(AporteDeResumen aporte, String clave) {
		return aporte.indicadores().stream()
				.filter(i -> i.clave().equals(clave))
				.findFirst()
				.orElseThrow();
	}

	/**
	 * Una obligacion en el estado que el caso necesita.
	 *
	 * <p>El saldo y el estado se fijan por reflexion en vez de simular cobros: lo que este test
	 * ejercita es la agregacion, y llegar a "PARCIAL con 3000.45" imputando cobros reales mezclaria
	 * la maquina de estados de M19 en un test que no la esta probando.
	 */
	private static Obligacion obligacion(long id, BigDecimal saldo, EstadoObligacion estado) {
		Obligacion obligacion = new Obligacion(
				ORG_ID, SEDE_ID, 1L, PERSONA_ID, Responsable.PACIENTE,
				new BigDecimal("9999.99"), "ARS", 2L, "Sesion de kinesiologia", Instant.now());
		ReflectionTestUtils.setField(obligacion, "id", id);
		ReflectionTestUtils.setField(obligacion, "saldo", saldo);
		ReflectionTestUtils.setField(obligacion, "estado", estado);
		return obligacion;
	}
}
