package com.akine.scheduling.infrastructure;

import com.akine.assistant.spi.ActorDelAsistente;
import com.akine.assistant.spi.PedidoDeHerramienta;
import com.akine.scheduling.application.FiltroDeTurnos;
import com.akine.scheduling.application.OperatingActor;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.TurnosDeSedeView;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnosEnElAsistenteTest {

	private static final ActorDelAsistente ACTOR = new ActorDelAsistente(11L, false, 7L, 3L, "ADMINISTRATIVO");
	private static final LocalDate MANANA = LocalDate.of(2026, 11, 16);

	private final RecepcionService recepcion = mock(RecepcionService.class);

	private static PedidoDeHerramienta pedido(Long personaId, LocalDate desde, LocalDate hasta) {
		return new PedidoDeHerramienta(personaId, null, "", desde, hasta, ZoneId.of("UTC"), null);
	}

	@Test
	void el_ultimo_dia_inclusive_del_pedido_va_como_hasta_exclusivo() {
		// Prueba de punta a punta del 15/11: "¿quienes vienen manana?" pedia [16/11, 16/11) y daba 400.
		when(recepcion.delRango(any(), anyLong(), any(), any(), any(), any(), anyBoolean(), any()))
				.thenReturn(new TurnosDeSedeView(MANANA, MANANA.plusDays(1), "UTC", List.of()));

		new TurnosEnElAsistente.AgendaDelDia(recepcion).ejecutar(ACTOR, pedido(null, MANANA, MANANA));

		verify(recepcion).delRango(any(OperatingActor.class), eq(3L), eq(MANANA), eq(MANANA.plusDays(1)),
				isNull(), isNull(), eq(true), eq(FiltroDeTurnos.NINGUNO));
	}

	@Test
	void filtra_por_la_persona_y_deja_propagar_el_rechazo_del_servicio() {
		when(recepcion.delRango(any(), anyLong(), any(), any(), any(), any(), anyBoolean(), any()))
				.thenThrow(new AccessDeniedException("sin turno:read"));

		// El asistente lo convierte en "sin permiso" en un solo lugar (AsistenteService.ejecutar).
		assertThatThrownBy(() -> new TurnosEnElAsistente.AsistenciaDePersona(recepcion)
				.ejecutar(ACTOR, pedido(5L, MANANA, MANANA.plusDays(6))))
				.isInstanceOf(AccessDeniedException.class);
		verify(recepcion).delRango(any(OperatingActor.class), eq(3L), eq(MANANA), eq(MANANA.plusDays(7)),
				isNull(), isNull(), eq(true), eq(new FiltroDeTurnos(Set.of(), 5L)));
	}
}
