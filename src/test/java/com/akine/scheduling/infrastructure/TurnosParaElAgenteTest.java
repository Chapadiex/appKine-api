package com.akine.scheduling.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.ContextoDeConsulta;
import com.akine.assistant.spi.ResultadoDeHerramienta;
import com.akine.person.spi.PacienteDirectory;
import com.akine.scheduling.application.FiltroDeTurnos;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.TurnosDeSedeView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TurnosParaElAgenteTest {

	private static final ActorDelAsistente ACTOR = new ActorDelAsistente(1, false, 10L, 20L, "ORG_ADMIN");
	private static final ContextoDeConsulta CTX = new ContextoDeConsulta(LocalDate.of(2026, 11, 23),
			ZoneId.of("America/Argentina/Cordoba"), null);

	@Test
	void personaDeOtraOrganizacionDaVacioYAdvertencia() {
		RecepcionService recepcion = mock(RecepcionService.class);
		PacienteDirectory pacientes = mock(PacienteDirectory.class);
		when(pacientes.find(anyLong(), anyLong())).thenReturn(Optional.empty());
		var turnos = new TurnosParaElAgente.Turnos(recepcion, pacientes);

		ResultadoDeHerramienta r = turnos.ejecutar(ACTOR, Map.of("persona_id", 999), CTX);

		assertThat(r.filas()).isEmpty();
		assertThat(r.advertencias()).containsExactly("No encontré esa persona");
		verifyNoInteractions(recepcion);
	}

	@Test
	void vinieronFiltraPresentesEnAtencionYAtendidos() {
		RecepcionService recepcion = mock(RecepcionService.class);
		TurnosDeSedeView vista = mock(TurnosDeSedeView.class);
		when(vista.turnos()).thenReturn(List.of());
		when(recepcion.delRango(any(), anyLong(), any(), any(), any(), any(), anyBoolean(), any()))
				.thenReturn(vista);
		var turnos = new TurnosParaElAgente.Turnos(recepcion, mock(PacienteDirectory.class));

		turnos.ejecutar(ACTOR, Map.of("vinieron", true), CTX);

		ArgumentCaptor<FiltroDeTurnos> filtro = ArgumentCaptor.forClass(FiltroDeTurnos.class);
		verify(recepcion).delRango(any(), eq(20L), any(), any(), any(), any(), anyBoolean(), filtro.capture());
		assertThat(filtro.getValue().estados()).containsExactlyInAnyOrder("PRESENTE", "EN_ATENCION", "ATENDIDO");
	}
}
