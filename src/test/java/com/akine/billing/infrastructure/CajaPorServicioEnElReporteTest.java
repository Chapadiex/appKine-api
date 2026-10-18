package com.akine.billing.infrastructure;

import com.akine.billing.domain.MedioDePago;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/** El reparto de la caja por servicio (DP-39), sin base: los numeros los da el IT contra MySQL. */
class CajaPorServicioEnElReporteTest {

	private static final long KINESIO = 10L;
	private static final long PILATES = 20L;

	@Test
	@DisplayName("Cada medio se reparte en proporcion a lo imputado, y lo no imputado es una parte mas")
	void reparte_por_medio_con_saldo_a_favor() {
		Map<Long, Map<MedioDePago, BigDecimal>> medios = new TreeMap<>();
		medios.put(1L, medios(MedioDePago.EFECTIVO, "100.00", MedioDePago.TRANSFERENCIA, "1100.00"));
		Map<Long, Map<Long, BigDecimal>> imputado = Map.of(
				1L, new TreeMap<>(Map.of(KINESIO, new BigDecimal("600.00"), PILATES, new BigDecimal("400.00"))));

		Map<Long, Map<MedioDePago, BigDecimal>> porOferta = CajaPorServicioEnElReporte.repartir(medios, imputado);

		assertThat(porOferta.get(KINESIO)).containsEntry(MedioDePago.EFECTIVO, new BigDecimal("50.00"))
				.containsEntry(MedioDePago.TRANSFERENCIA, new BigDecimal("550.00"));
		assertThat(porOferta.get(PILATES)).containsEntry(MedioDePago.EFECTIVO, new BigDecimal("33.33"))
				.containsEntry(MedioDePago.TRANSFERENCIA, new BigDecimal("366.67"));
		assertThat(porOferta.get(CajaPorServicioEnElReporte.SIN_IMPUTAR))
				.containsEntry(MedioDePago.EFECTIVO, new BigDecimal("16.67"))
				.containsEntry(MedioDePago.TRANSFERENCIA, new BigDecimal("183.33"));
	}

	@Test
	@DisplayName("Un anticipo puro va entero a sin imputar, y varios cobros se acumulan por oferta")
	void anticipo_puro_y_acumulacion() {
		Map<Long, Map<MedioDePago, BigDecimal>> medios = new TreeMap<>();
		medios.put(1L, medios(MedioDePago.EFECTIVO, "300.00"));
		medios.put(2L, medios(MedioDePago.EFECTIVO, "50.00"));
		medios.put(3L, medios(MedioDePago.TARJETA_DEBITO, "70.00"));
		Map<Long, Map<Long, BigDecimal>> imputado = Map.of(
				2L, Map.of(KINESIO, new BigDecimal("50.00")),
				3L, Map.of(KINESIO, new BigDecimal("70.00")));

		Map<Long, Map<MedioDePago, BigDecimal>> porOferta = CajaPorServicioEnElReporte.repartir(medios, imputado);

		assertThat(porOferta.get(CajaPorServicioEnElReporte.SIN_IMPUTAR))
				.containsExactly(Map.entry(MedioDePago.EFECTIVO, new BigDecimal("300.00")));
		assertThat(porOferta.get(KINESIO))
				.containsEntry(MedioDePago.EFECTIVO, new BigDecimal("50.00"))
				.containsEntry(MedioDePago.TARJETA_DEBITO, new BigDecimal("70.00"));
	}

	private static Map<MedioDePago, BigDecimal> medios(Object... medioEImporte) {
		Map<MedioDePago, BigDecimal> medios = new EnumMap<>(MedioDePago.class);
		for (int i = 0; i < medioEImporte.length; i += 2) {
			medios.put((MedioDePago) medioEImporte[i], new BigDecimal((String) medioEImporte[i + 1]));
		}
		return medios;
	}
}
