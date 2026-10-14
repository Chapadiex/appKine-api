package com.akine.organization.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class ZonaDeSedeTest {

	/** 01:30 UTC del 15/03 = 22:30 del 14/03 en Cordoba. */
	private static final Clock A_LAS_22_30_DE_CORDOBA =
			Clock.fixed(Instant.parse("2026-03-15T01:30:00Z"), ZoneOffset.UTC);

	@Test
	@DisplayName("a las 22:30 de Cordoba, hoy en la sede sigue siendo el 14 aunque en UTC ya sea el 15")
	void hoy_es_el_de_la_sede() {
		ConsultorioSnapshot sede = sede("America/Argentina/Cordoba");

		assertThat(ZonaDeSede.hoy(sede, A_LAS_22_30_DE_CORDOBA)).isEqualTo(LocalDate.of(2026, 3, 14));
	}

	@Test
	@DisplayName("una zona invalida o en blanco cae a UTC sin fallar")
	void zona_invalida_cae_a_utc() {
		assertThat(ZonaDeSede.de(sede("Marte/Olympus"))).isEqualTo(ZoneOffset.UTC);
		assertThat(ZonaDeSede.de(sede(" "))).isEqualTo(ZoneOffset.UTC);
	}

	@Test
	@DisplayName("una sede que no resuelve cae a UTC")
	void sede_que_no_resuelve_cae_a_utc() {
		ConsultorioDirectory consultorios = mock(ConsultorioDirectory.class);
		given(consultorios.find(1L, 2L)).willReturn(Optional.empty());

		assertThat(ZonaDeSede.hoy(consultorios, 1L, 2L, A_LAS_22_30_DE_CORDOBA))
				.isEqualTo(LocalDate.of(2026, 3, 15));
	}

	private static ConsultorioSnapshot sede(String zona) {
		return new ConsultorioSnapshot(2L, 1L, "Sede", zona, true);
	}
}
