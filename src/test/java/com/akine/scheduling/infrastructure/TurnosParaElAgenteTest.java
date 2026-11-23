package com.akine.scheduling.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.person.spi.PacienteDirectory;
import com.akine.scheduling.application.RecepcionService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TurnosParaElAgenteTest {

	@Test
	void personaDeOtraOrganizacionDaVacioYAdvertencia() {
		RecepcionService recepcion = mock(RecepcionService.class);
		PacienteDirectory pacientes = mock(PacienteDirectory.class);
		when(pacientes.find(anyLong(), anyLong())).thenReturn(Optional.empty());
		var turnos = new TurnosParaElAgente.Turnos(recepcion, pacientes);

		ResultadoDeHerramienta r = turnos.ejecutar(new ActorDelAsistente(1, false, 10L, 20L, "ORG_ADMIN"),
				Map.of("persona_id", 999), new ContextoDeConsulta(LocalDate.of(2026, 11, 23),
						ZoneId.of("America/Argentina/Cordoba"), null));

		assertThat(r.filas()).isEmpty();
		assertThat(r.advertencias()).containsExactly("No encontré esa persona");
		verifyNoInteractions(recepcion);
	}
}
