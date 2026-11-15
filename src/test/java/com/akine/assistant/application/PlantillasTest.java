package com.akine.assistant.application;

import com.akine.assistant.spi.ClavesDeFila;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlantillasTest {

	private static final ZoneId CORDOBA = ZoneId.of("America/Argentina/Cordoba");
	private static final Instant AHORA = Instant.parse("2026-11-15T15:00:00Z");
	private static final RangoDeDias SEMANA = new RangoDeDias(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 15));

	private static Map<String, Object> turno(String inicio, String estado) {
		return Map.of(ClavesDeFila.INICIO, Instant.parse(inicio), ClavesDeFila.ESTADO, estado);
	}

	@Test
	void sin_ninguna_llegada_lista_los_turnos_que_tenia_en_una_sola_frase() {
		String texto = Plantillas.asistencia("PruebaAviso, Marta", SEMANA, CORDOBA, "Centro QA A", List.of(
				turno("2026-11-09T12:00:00Z", "RESERVADO"), turno("2026-11-11T15:00:00Z", "CONFIRMADO")), AHORA);

		assertThat(texto).isEqualTo("PruebaAviso, Marta no tiene llegadas registradas entre el 9/11 y el 15/11 en "
				+ "Centro QA A: tenía turno lun 9/11 9:00, mié 11/11 12:00.");
	}

	@Test
	void separa_lo_que_vino_lo_pasado_sin_llegada_y_lo_proximo() {
		String texto = Plantillas.asistencia("Acosta, Lautaro", SEMANA, CORDOBA, "Centro QA A", List.of(
				turno("2026-11-10T13:00:00Z", "RESERVADO"),
				turno("2026-11-13T13:00:00Z", "ATENDIDO"),
				turno("2026-11-15T19:00:00Z", "RESERVADO")), AHORA);

		assertThat(texto).isEqualTo("Acosta, Lautaro vino 1 vez entre el 9/11 y el 15/11 en Centro QA A: "
				+ "vie 13/11 10:00 (atendido). Sin llegada registrada: mar 10/11 10:00. Próximos: dom 15/11 16:00.");
	}
}
