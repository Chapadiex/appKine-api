package com.akine.scheduling.application;

import com.akine.scheduling.domain.OrigenDeCancelacion;
import com.akine.scheduling.domain.Turno;
import com.akine.scheduling.domain.exception.TurnoConAtencionException;
import com.akine.scheduling.domain.exception.TurnoNotAccessibleException;
import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.TurnoRepositoryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Cancelacion en lote (checklist de agenda §6.4): un rechazo no deshace los demas, cada item vuelve con
 * el {@code problemType} que habria recibido solo, y reenviar el lote no es un error.
 */
class CancelacionEnLoteServiceTest {

	private static final long ORG_ID = 10L;
	private static final long SEDE_ID = 20L;
	private static final String MOTIVO = "Licencia del profesional";
	private static final Instant MANANA = Instant.now().plusSeconds(86_400);

	private final CicloDeTurnoService ciclo = mock(CicloDeTurnoService.class);
	private final TurnoRepositoryPort turnos = mock(TurnoRepositoryPort.class);
	private final CancelacionEnLoteService service = new CancelacionEnLoteService(ciclo, turnos);
	private final OperatingActor actor = new OperatingActor(40L, false, ORG_ID, SEDE_ID);

	@Test
	@DisplayName("resultado por item: cancelado, rechazado con su tipo, ya cancelado y sin permiso")
	void resultados_parciales() {
		Turno cancelado = turno(1L);
		cancelado.cancelar(MOTIVO, OrigenDeCancelacion.PROFESIONAL, 40L, Instant.now());
		given(ciclo.cancelar(any(), eq(SEDE_ID), eq(1L), any(), any(), anyLong())).willReturn(TurnoView.de(cancelado));
		given(ciclo.cancelar(any(), eq(SEDE_ID), eq(2L), any(), any(), anyLong()))
				.willThrow(new TurnoConAtencionException(2L));
		given(turnos.findByIdInScope(ORG_ID, SEDE_ID, 2L)).willReturn(Optional.of(turno(2L)));
		// El reintento del lote: la version leida ya no es la actual porque el primer envio lo cancelo.
		given(ciclo.cancelar(any(), eq(SEDE_ID), eq(3L), any(), any(), anyLong()))
				.willThrow(new OptimisticLockingFailureException("version vieja"));
		Turno yaCancelado = turno(3L);
		yaCancelado.cancelar(MOTIVO, null, 40L, Instant.now());
		given(turnos.findByIdInScope(ORG_ID, SEDE_ID, 3L)).willReturn(Optional.of(yaCancelado));
		given(ciclo.cancelar(any(), eq(SEDE_ID), eq(4L), any(), any(), anyLong()))
				.willThrow(new TurnoNotAccessibleException(4L));
		given(ciclo.cancelar(any(), eq(SEDE_ID), eq(5L), any(), any(), anyLong()))
				.willThrow(new AccessDeniedException("ajeno"));

		var resultados = service.cancelar(actor, SEDE_ID, List.of(
				new CancelacionEnLoteService.Item(1L, 0L),
				new CancelacionEnLoteService.Item(2L, 0L),
				new CancelacionEnLoteService.Item(3L, 0L),
				new CancelacionEnLoteService.Item(4L, 0L),
				new CancelacionEnLoteService.Item(5L, 0L),
				new CancelacionEnLoteService.Item(1L, 0L)), MOTIVO, OrigenDeCancelacion.PROFESIONAL);

		assertThat(resultados)
				.extracting(CancelacionEnLoteService.ResultadoDeItem::turnoId,
						CancelacionEnLoteService.ResultadoDeItem::resultado,
						CancelacionEnLoteService.ResultadoDeItem::problemType)
				.containsExactly(
						tuple(1L, "CANCELADO", null),
						tuple(2L, "RECHAZADO", "https://akine.app/problems/turno-con-atencion"),
						tuple(3L, "YA_CANCELADO", null),
						tuple(4L, "RECHAZADO", "https://akine.app/problems/not-found"),
						tuple(5L, "RECHAZADO", "https://akine.app/problems/forbidden"));
		assertThat(resultados.get(1).turno().estado()).isEqualTo("RESERVADO");
		// El id repetido se proceso una sola vez.
		verify(ciclo, times(1)).cancelar(any(), eq(SEDE_ID), eq(1L), eq(MOTIVO), eq(OrigenDeCancelacion.PROFESIONAL),
				eq(0L));
	}

	@Test
	@DisplayName("un lote vacio o sin motivo se rechaza entero, antes de tocar un turno")
	void lote_invalido() {
		assertThatThrownBy(() -> service.cancelar(actor, SEDE_ID, List.of(), MOTIVO, null))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> service.cancelar(actor, SEDE_ID,
				List.of(new CancelacionEnLoteService.Item(1L, 0L)), " ", null))
				.isInstanceOf(IllegalArgumentException.class);
		verify(ciclo, times(0)).cancelar(any(), anyLong(), anyLong(), any(), any(), anyLong());
	}

	private static Turno turno(long id) {
		Turno turno = new Turno(ORG_ID, SEDE_ID, 4L, 128L, 31L, null, MANANA, MANANA.plusSeconds(3600),
				40L, Instant.now(), null, null);
		ReflectionTestUtils.setField(turno, "id", id);
		return turno;
	}
}
