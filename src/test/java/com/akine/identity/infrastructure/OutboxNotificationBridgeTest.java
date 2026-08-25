package com.akine.identity.infrastructure;

import com.akine.identity.domain.port.NotificationOutboxPort;
import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationOutbox;
import com.akine.notification.spi.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * Puente entre el puerto de identidad y el outbox.
 *
 * <p>El test central es
 * {@link #el_enlace_no_entra_al_comando_que_se_persiste()}: si alguien decide "simplificar"
 * metiendo el enlace en los datos de render, el token de activacion queda en una tabla que se
 * consulta para diagnosticar entregas y que termina en los backups. Eso es una credencial
 * persistida, que es exactamente lo que RN-M02-003 prohibe.
 */
@ExtendWith(MockitoExtension.class)
class OutboxNotificationBridgeTest {

	private static final String ENLACE = "https://app.akine.test/activar?token=secreto";

	@Mock
	private NotificationOutbox notificationOutbox;

	private final SecureLinkVault vault = vaultDePrueba();

	private static SecureLinkVault vaultDePrueba() {
		IdentityProperties properties = new IdentityProperties();
		properties.getLinks().setTtl(Duration.ofMinutes(10));
		return new SecureLinkVault(properties);
	}

	private OutboxNotificationBridge bridge() {
		return new OutboxNotificationBridge(notificationOutbox, vault);
	}

	private NotificationEnqueueCommand capturarComando() {
		ArgumentCaptor<NotificationEnqueueCommand> captor =
				ArgumentCaptor.forClass(NotificationEnqueueCommand.class);
		verify(notificationOutbox).enqueue(captor.capture());
		return captor.getValue();
	}

	@Test
	@DisplayName("el enlace no entra al comando que se persiste: solo la referencia al token")
	void el_enlace_no_entra_al_comando_que_se_persiste() {
		given(notificationOutbox.enqueue(any())).willReturn(1L);

		bridge().encolar(new NotificationOutboxPort.Notificacion(
				10L,
				NotificationOutboxPort.TipoNotificacion.ACTIVACION_CUENTA,
				"ana@ejemplo.test",
				Map.of("nombre", "Ana"),
				ENLACE,
				"activacion:500"));

		NotificationEnqueueCommand comando = capturarComando();
		assertThat(comando.referenciaTokenId()).isEqualTo("500");
		assertThat(comando.datosDeRender()).containsExactly(Map.entry("nombre", "Ana"));
		assertThat(comando.datosDeRender().values()).noneMatch(valor -> valor.contains("token"));
		assertThat(comando.toString()).doesNotContain("secreto");
	}

	@Test
	@DisplayName("el enlace queda disponible en memoria para el envio, y en ningun otro lado")
	void el_enlace_queda_en_memoria_para_el_envio() {
		given(notificationOutbox.enqueue(any())).willReturn(1L);

		bridge().encolar(new NotificationOutboxPort.Notificacion(
				10L,
				NotificationOutboxPort.TipoNotificacion.RESET_PASSWORD,
				"ana@ejemplo.test",
				Map.of(),
				ENLACE,
				"reset:500"));

		assertThat(vault.leer("500", Instant.now())).contains(ENLACE);
	}

	@Test
	@DisplayName("el aviso de cuenta ya registrada no lleva referencia ni enlace")
	void el_aviso_de_cuenta_ya_registrada_no_lleva_enlace() {
		given(notificationOutbox.enqueue(any())).willReturn(1L);

		bridge().encolar(new NotificationOutboxPort.Notificacion(
				null,
				NotificationOutboxPort.TipoNotificacion.CUENTA_YA_REGISTRADA,
				"ana@ejemplo.test",
				Map.of(),
				null,
				"registro-duplicado:clave-1"));

		NotificationEnqueueCommand comando = capturarComando();
		assertThat(comando.tipo()).isEqualTo(NotificationType.CUENTA_YA_REGISTRADA);
		assertThat(comando.referenciaTokenId()).isNull();
		assertThat(comando.organizationId()).isNull();
		assertThat(vault.pendientes()).isZero();
	}

	@Test
	@DisplayName("los tres tipos de identidad se traducen a los del spi")
	void los_tipos_se_traducen() {
		given(notificationOutbox.enqueue(any())).willReturn(1L);
		OutboxNotificationBridge bridge = bridge();

		bridge.encolar(new NotificationOutboxPort.Notificacion(
				null, NotificationOutboxPort.TipoNotificacion.RESET_PASSWORD,
				"ana@ejemplo.test", Map.of(), ENLACE, "reset:7"));

		assertThat(capturarComando().tipo()).isEqualTo(NotificationType.RECUPERACION_PASSWORD);
	}

	@Test
	@DisplayName("una clave sin separador se usa entera como referencia")
	void una_clave_sin_separador_se_usa_entera() {
		given(notificationOutbox.enqueue(any())).willReturn(1L);

		bridge().encolar(new NotificationOutboxPort.Notificacion(
				null, NotificationOutboxPort.TipoNotificacion.ACTIVACION_CUENTA,
				"ana@ejemplo.test", Map.of(), ENLACE, "500"));

		assertThat(capturarComando().referenciaTokenId()).isEqualTo("500");
	}

	@Test
	@DisplayName("encolar nada es un error de programacion")
	void encolar_nada_falla() {
		assertThatThrownBy(() -> bridge().encolar(null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
