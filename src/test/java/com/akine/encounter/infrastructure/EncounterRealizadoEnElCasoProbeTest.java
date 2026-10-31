package com.akine.encounter.infrastructure;

import com.akine.clinical.spi.RealizadoPorOferta;
import com.akine.encounter.domain.ConteoDeSesionesPorOferta;
import com.akine.encounter.domain.port.SesionRepositoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * La sonda con la que M11 deriva el avance del plan desde las sesiones (AKINE-04.04).
 *
 * <p>Una sola regla: un conteo nulo se lee como cero. Un {@code NullPointerException} en el
 * desempaquetado dejaria sin avance la pantalla entera del plan por una fila rara.
 */
@ExtendWith(MockitoExtension.class)
class EncounterRealizadoEnElCasoProbeTest {

	@Mock private SesionRepositoryPort sesiones;

	@Test
	@DisplayName("Traduce el conteo por oferta y lee un conteo nulo como cero")
	void traduce_y_normaliza_los_nulos() {
		given(sesiones.contarCerradasPorOferta(1L, 55L)).willReturn(List.of(
				new ConteoDeSesionesPorOferta(42L, 6L, 1L),
				new ConteoDeSesionesPorOferta(43L, null, null)));

		List<RealizadoPorOferta> conteos =
				new EncounterRealizadoEnElCasoProbe(sesiones).contarPorOfertaEnElCaso(1L, 55L);

		assertThat(conteos).containsExactly(
				new RealizadoPorOferta(42L, 6, 1),
				new RealizadoPorOferta(43L, 0, 0));
	}

	@Test
	@DisplayName("Desde una fecha cuenta solo lo cerrado desde entonces; un plan sin activar no tiene de donde partir y cuenta todo")
	void cuenta_desde_la_activacion() {
		Instant activacion = Instant.parse("2027-03-08T12:00:00Z");
		given(sesiones.contarCerradasPorOfertaDesde(1L, 55L, activacion)).willReturn(List.of(
				new ConteoDeSesionesPorOferta(42L, 2L, null)));
		given(sesiones.contarCerradasPorOferta(1L, 55L)).willReturn(List.of(
				new ConteoDeSesionesPorOferta(42L, 6L, 1L)));

		EncounterRealizadoEnElCasoProbe sonda = new EncounterRealizadoEnElCasoProbe(sesiones);

		assertThat(sonda.contarPorOfertaEnElCasoDesde(1L, 55L, activacion)).containsExactly(new RealizadoPorOferta(42L, 2, 0));
		assertThat(sonda.contarPorOfertaEnElCasoDesde(1L, 55L, null)).containsExactly(new RealizadoPorOferta(42L, 6, 1));
	}
}
