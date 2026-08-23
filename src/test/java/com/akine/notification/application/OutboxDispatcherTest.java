package com.akine.notification.application;

import com.akine.notification.domain.EmailMessage;
import com.akine.notification.domain.SanitizedPayload;
import com.akine.notification.domain.exception.EmailDeliveryException;
import com.akine.notification.domain.port.EmailSender;
import com.akine.notification.spi.NotificationType;
import com.akine.notification.spi.SecureLinkResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.akine.notification.NotificationFixtures.DESTINATARIO;
import static com.akine.notification.NotificationFixtures.TOKEN_REF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Un tick del worker: recuperar lo huerfano, reclamar un lote y entregarlo.
 *
 * <p>Lo que estos tests sostienen: que cada notificacion se resuelva por separado —una direccion
 * invalida en la primera fila no puede impedir que salgan las otras diecinueve—, que un token ya
 * consumido no se reintente (reenviar un enlace muerto no ayuda a nadie, T-11), y que ningun
 * motivo de fallo llegue al outbox con un enlace o una credencial adentro.
 */
@ExtendWith(MockitoExtension.class)
class OutboxDispatcherTest {

	private static final long ID_ACTIVACION = 1L;
	private static final long ID_AVISO = 2L;
	private static final String ENLACE = "https://app.akine.test/activar?token=SINTETICO";

	@Mock
	private OutboxDispatchService dispatchService;

	@Mock
	private EmailSender emailSender;

	@Mock
	private SecureLinkResolver linkResolver;

	private OutboxDispatcher dispatcher;

	private OutboxDispatcher dispatcher() {
		if (dispatcher == null) {
			dispatcher = new OutboxDispatcher(dispatchService, emailSender, linkResolver);
		}
		return dispatcher;
	}

	/** Notificacion que necesita reconstruir el enlace antes de redactarse. */
	private PendingDelivery activacion() {
		return new PendingDelivery(
				ID_ACTIVACION,
				NotificationType.ACTIVACION_CUENTA,
				DESTINATARIO,
				SanitizedPayload.of(Map.of("nombre", "Ana")),
				TOKEN_REF,
				0);
	}

	/** Notificacion sin enlace: el aviso de cuenta ya registrada. */
	private PendingDelivery aviso() {
		return new PendingDelivery(
				ID_AVISO,
				NotificationType.CUENTA_YA_REGISTRADA,
				DESTINATARIO,
				SanitizedPayload.vacio(),
				null,
				0);
	}

	private void lote(PendingDelivery... pendientes) {
		given(dispatchService.reclamarLote()).willReturn(List.of(pendientes));
	}

	private String motivoTransitorio() {
		ArgumentCaptor<String> motivo = ArgumentCaptor.forClass(String.class);
		verify(dispatchService).registrarFalloTransitorio(anyLong(), motivo.capture());
		return motivo.getValue();
	}

	private String motivoPermanente() {
		ArgumentCaptor<String> motivo = ArgumentCaptor.forClass(String.class);
		verify(dispatchService).registrarFalloPermanente(anyLong(), motivo.capture());
		return motivo.getValue();
	}

	@Test
	@DisplayName("un tick recupera lo huerfano antes de reclamar y devuelve cuantas intento")
	void un_tick_recupera_antes_de_reclamar() {
		// El orden importa: si reclamara primero, las huerfanas recuperadas en este mismo tick
		// tendrian que esperar al siguiente para siquiera ser candidatas.
		lote(activacion(), aviso());
		given(linkResolver.resolveLink(NotificationType.ACTIVACION_CUENTA, TOKEN_REF))
				.willReturn(Optional.of(ENLACE));

		int intentadas = dispatcher().runOnce();

		assertThat(intentadas).isEqualTo(2);
		verify(dispatchService).recuperarLeasesVencidos();
		verify(dispatchService).registrarExito(ID_ACTIVACION);
		verify(dispatchService).registrarExito(ID_AVISO);
	}

	@Test
	@DisplayName("un tick sin nada que entregar no manda ningun mail")
	void un_tick_vacio_no_manda_nada() {
		lote();

		assertThat(dispatcher().runOnce()).isZero();
		verifyNoInteractions(emailSender, linkResolver);
	}

	@Test
	@DisplayName("el enlace se reconstruye recien al enviar y viaja solo dentro del mensaje")
	void el_enlace_se_reconstruye_al_enviar() {
		lote(activacion());
		given(linkResolver.resolveLink(NotificationType.ACTIVACION_CUENTA, TOKEN_REF))
				.willReturn(Optional.of(ENLACE));

		dispatcher().runOnce();

		ArgumentCaptor<EmailMessage> enviado = ArgumentCaptor.forClass(EmailMessage.class);
		verify(emailSender).send(enviado.capture());
		assertThat(enviado.getValue().destinatario()).isEqualTo(DESTINATARIO);
		assertThat(enviado.getValue().cuerpo()).contains(ENLACE).contains("Hola Ana");
		verify(dispatchService).registrarExito(ID_ACTIVACION);
	}

	@Test
	@DisplayName("una notificacion sin enlace no consulta al resolver")
	void sin_enlace_no_se_consulta_al_resolver() {
		// El aviso de cuenta ya registrada no tiene token asociado: consultar igual seria pedir
		// un enlace por una referencia nula y convertir un caso normal en un fallo.
		lote(aviso());

		dispatcher().runOnce();

		verifyNoInteractions(linkResolver);
		verify(emailSender).send(any());
		verify(dispatchService).registrarExito(ID_AVISO);
	}

	@Test
	@DisplayName("un token ya consumido cierra la fila sin reintentar y sin mandar el mail")
	void un_token_muerto_no_se_reintenta() {
		// Reenviar un enlace vencido no ayuda a nadie: el reintento daria exactamente lo mismo
		// y solo gastaria la cuota (T-11).
		lote(activacion());
		given(linkResolver.resolveLink(NotificationType.ACTIVACION_CUENTA, TOKEN_REF))
				.willReturn(Optional.empty());

		dispatcher().runOnce();

		verifyNoInteractions(emailSender);
		verify(dispatchService, never()).registrarFalloTransitorio(anyLong(), anyString());
		assertThat(motivoPermanente()).contains("token");
	}

	@Test
	@DisplayName("un fallo transitorio del canal devuelve la notificacion a la cola")
	void un_fallo_transitorio_vuelve_a_la_cola() {
		lote(aviso());
		org.mockito.BDDMockito.willThrow(EmailDeliveryException.transitorio(
						"el relay no responde", new java.net.SocketTimeoutException("read timed out")))
				.given(emailSender).send(any());

		dispatcher().runOnce();

		verify(dispatchService).registrarFalloTransitorio(eq(ID_AVISO), anyString());
		verify(dispatchService, never()).registrarExito(anyLong());
	}

	@Test
	@DisplayName("un fallo permanente del canal cierra la fila sin gastar reintentos")
	void un_fallo_permanente_cierra_la_fila() {
		lote(aviso());
		org.mockito.BDDMockito.willThrow(
						EmailDeliveryException.permanente("direccion de destino invalida"))
				.given(emailSender).send(any());

		dispatcher().runOnce();

		verify(dispatchService).registrarFalloPermanente(eq(ID_AVISO), anyString());
		verify(dispatchService, never()).registrarFalloTransitorio(anyLong(), anyString());
	}

	@Test
	@DisplayName("datos mal formados no se reintentan: el resultado seria identico")
	void los_datos_mal_formados_no_se_reintentan() {
		lote(aviso());
		org.mockito.BDDMockito.willThrow(new IllegalStateException("template sin asunto"))
				.given(emailSender).send(any());

		dispatcher().runOnce();

		verify(dispatchService).registrarFalloPermanente(eq(ID_AVISO), anyString());
	}

	@Test
	@DisplayName("un fallo inesperado se trata como transitorio y no descarta la notificacion")
	void lo_inesperado_se_trata_como_transitorio() {
		// Puede ser un corte de red o una dependencia caida un instante: descartar la
		// notificacion por las dudas es peor que reintentarla unas pocas veces mas.
		lote(aviso());
		org.mockito.BDDMockito.willThrow(new NullPointerException("adaptador sin configurar"))
				.given(emailSender).send(any());

		dispatcher().runOnce();

		verify(dispatchService).registrarFalloTransitorio(eq(ID_AVISO), anyString());
	}

	@Test
	@DisplayName("el motivo que se registra nunca lleva el enlace ni las credenciales del relay")
	void el_motivo_registrado_no_filtra_secretos() {
		lote(aviso());
		org.mockito.BDDMockito.willThrow(EmailDeliveryException.transitorio(
						"rechazo de smtp.proveedor.test:587 con password=hunter2 al mandar "
								+ ENLACE + " a " + DESTINATARIO, null))
				.given(emailSender).send(any());

		dispatcher().runOnce();

		// El motivo se guarda en error_sanitizado, que se lee desde una pantalla administrativa
		// y termina en los backups.
		assertThat(motivoTransitorio())
				.doesNotContain("SINTETICO")
				.doesNotContain("hunter2")
				.doesNotContain("smtp.proveedor.test:587")
				.doesNotContain(DESTINATARIO)
				.contains("EmailDeliveryException");
	}

	@Test
	@DisplayName("una notificacion que falla no arrastra al resto del lote")
	void un_fallo_no_arrastra_al_lote() {
		// Sin try/catch por fila, una direccion invalida en la primera notificacion dejaria sin
		// entregar a las diecinueve siguientes, y ninguna quedaria marcada como fallida.
		lote(activacion(), aviso());
		given(linkResolver.resolveLink(NotificationType.ACTIVACION_CUENTA, TOKEN_REF))
				.willReturn(Optional.empty());

		int intentadas = dispatcher().runOnce();

		assertThat(intentadas).isEqualTo(2);
		verify(dispatchService).registrarFalloPermanente(eq(ID_ACTIVACION), anyString());
		verify(dispatchService).registrarExito(ID_AVISO);
		verify(emailSender).send(any());
	}
}
