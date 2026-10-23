package com.akine.reporting.spi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DP-45: el periodo partido en tramos de desfase constante, para agrupar por dia sin tablas de zonas. */
class ConsultaDeReporteTramosTest {

	@Test
	@DisplayName("Una zona sin horario de verano da un solo tramo, con el desfase numerico")
	void zona_sin_transiciones() {
		List<ConsultaDeReporte.TramoHorario> tramos =
				consulta("America/Argentina/Cordoba", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
						.tramosHorarios();

		assertThat(tramos).singleElement()
				.satisfies(t -> assertThat(t.desfase()).isEqualTo("-03:00"));
	}

	@Test
	@DisplayName("UTC se escribe +00:00 y no Z, que CONVERT_TZ no entiende")
	void utc() {
		assertThat(consulta("UTC", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 2)).tramosHorarios())
				.singleElement()
				.satisfies(t -> assertThat(t.desfase()).isEqualTo("+00:00"));
	}

	@Test
	@DisplayName("Con horario de verano se parte en la transicion, y los tramos cubren la ventana sin huecos")
	void zona_con_transicion() {
		ConsultaDeReporte consulta =
				consulta("Europe/Madrid", LocalDate.of(2026, 3, 27), LocalDate.of(2026, 3, 31));
		List<ConsultaDeReporte.TramoHorario> tramos = consulta.tramosHorarios();

		assertThat(tramos).extracting(ConsultaDeReporte.TramoHorario::desfase)
				.containsExactly("+01:00", "+02:00");
		assertThat(tramos.get(0).desde()).isEqualTo(consulta.desdeInstante());
		assertThat(tramos.get(0).hasta()).isEqualTo(tramos.get(1).desde());
		assertThat(tramos.get(1).hasta()).isEqualTo(consulta.hastaInstante());
		assertThat(tramos.get(1).desde()).isEqualTo(Instant.parse("2026-03-29T01:00:00Z"));
	}

	private static ConsultaDeReporte consulta(String zona, LocalDate desde, LocalDate hasta) {
		ZoneId id = ZoneId.of(zona);
		return new ConsultaDeReporte(1L, 2L, desde, hasta, id,
				desde.atStartOfDay(id).toInstant(), hasta.plusDays(1).atStartOfDay(id).toInstant(), 500);
	}
}
