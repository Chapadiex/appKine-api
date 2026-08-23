package com.akine.notification.application;

import com.akine.notification.NotificationFixtures;
import com.akine.notification.domain.NotificationOutboxEntry;
import com.akine.notification.domain.OutboxStatus;
import com.akine.notification.domain.OutboxWorkerSettings;
import com.akine.notification.domain.exception.SensitiveContentException;
import com.akine.notification.domain.port.NotificationClock;
import com.akine.notification.domain.port.NotificationOutboxRepositoryPort;
import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static com.akine.notification.NotificationFixtures.AHORA;
import static com.akine.notification.NotificationFixtures.CLAVE;
import static com.akine.notification.NotificationFixtures.DESTINATARIO;
import static com.akine.notification.NotificationFixtures.ENTRY_ID;
import static com.akine.notification.NotificationFixtures.ORG_ID;
import static com.akine.notification.NotificationFixtures.TOKEN_REF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Encolado de notificaciones (RF-M26-005, RN-M26-003).
 *
 * <p>Lo que estos tests sostienen: que reintentar el hecho de negocio no produzca una segunda
 * notificacion, y que nada con pinta de token o de enlace llegue a la fila.
 */
@ExtendWith(MockitoExtension.class)
class OutboxEnqueueServiceTest {

	@Mock
	private NotificationOutboxRepositoryPort repository;

	@Mock
	private NotificationClock clock;

	private final OutboxWorkerSettings settings = OutboxWorkerSettings.porDefecto();

	private OutboxEnqueueService service;

	private OutboxEnqueueService service() {
		if (service == null) {
			service = new OutboxEnqueueService(repository, clock, settings);
		}
		return service;
	}

	private NotificationEnqueueCommand comando(Map<String, String> datos) {
		return new NotificationEnqueueCommand(
				NotificationType.ACTIVACION_CUENTA, DESTINATARIO, CLAVE, ORG_ID, TOKEN_REF, datos);
	}

	@Test
	@DisplayName("encolar sin comando es un error de programacion del productor")
	void sin_comando_no_se_encola() {
		assertThatThrownBy(() -> service().enqueue(null))
				.isInstanceOf(IllegalArgumentException.class);

		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("una notificacion nueva se guarda PENDIENTE con los maximos intentos de la politica")
	void una_notificacion_nueva_se_guarda_pendiente() {
		given(repository.findByClaveIdempotente(CLAVE)).willReturn(Optional.empty());
		given(clock.now()).willReturn(AHORA);
		given(repository.save(any())).willAnswer(invocacion ->
				NotificationFixtures.conId(invocacion.getArgument(0), ENTRY_ID));

		long id = service().enqueue(comando(Map.of("nombre", "Ana")));

		assertThat(id).isEqualTo(ENTRY_ID);

		ArgumentCaptor<NotificationOutboxEntry> guardada =
				ArgumentCaptor.forClass(NotificationOutboxEntry.class);
		verify(repository).save(guardada.capture());
		NotificationOutboxEntry entry = guardada.getValue();
		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.PENDIENTE);
		assertThat(entry.getIntentos()).isZero();
		assertThat(entry.getMaxIntentos()).isEqualTo(settings.backoff().maxIntentos());
		// Sin espera inicial: la notificacion sale en el primer tick posterior al commit.
		assertThat(entry.getProximaEjecucionEn()).isEqualTo(AHORA);
		assertThat(entry.getClaveIdempotente()).isEqualTo(CLAVE);
		assertThat(entry.getOrganizationId()).isEqualTo(ORG_ID);
		assertThat(entry.getReferenciaTokenId()).isEqualTo(TOKEN_REF);
		assertThat(entry.payload().get("nombre")).isEqualTo("Ana");
	}

	@Test
	@DisplayName("un reintento del productor devuelve la notificacion existente y no crea otra")
	void el_reintento_del_productor_no_duplica() {
		// El productor puede reintentar su transaccion entera: si esto insertara de nuevo, la
		// persona recibiria dos mails de activacion del mismo registro (RN-M26-003).
		NotificationOutboxEntry existente = NotificationFixtures.pendiente();
		given(repository.findByClaveIdempotente(CLAVE)).willReturn(Optional.of(existente));

		long id = service().enqueue(comando(Map.of()));

		assertThat(id).isEqualTo(ENTRY_ID);
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("un reintento sobre una notificacion ya entregada tampoco vuelve a encolar")
	void el_reintento_sobre_una_entregada_no_reencola() {
		// La idempotencia es por clave, no por estado: si mirara el estado, una notificacion ya
		// ENVIADA se volveria a encolar y saldria dos veces.
		NotificationOutboxEntry entregada =
				NotificationFixtures.en(OutboxStatus.ENVIADA);
		given(repository.findByClaveIdempotente(CLAVE)).willReturn(Optional.of(entregada));

		assertThat(service().enqueue(comando(Map.of()))).isEqualTo(ENTRY_ID);
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("un dato de render con pinta de enlace hace fallar el encolado, no se guarda")
	void un_dato_sospechoso_impide_el_encolado() {
		// Falla la transaccion del productor entera, que es lo correcto: es un bug del codigo
		// que encola y se corrige ahi, no reintentando.
		given(repository.findByClaveIdempotente(CLAVE)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service().enqueue(
				comando(Map.of("nombre", "https://app.akine.test/activar?token=SINTETICO"))))
				.isInstanceOf(SensitiveContentException.class);

		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("una clave de render fuera de la lista blanca hace fallar el encolado")
	void una_clave_fuera_de_la_lista_blanca_impide_el_encolado() {
		given(repository.findByClaveIdempotente(CLAVE)).willReturn(Optional.empty());

		assertThatThrownBy(() -> service().enqueue(comando(Map.of("enlace", "algo"))))
				.isInstanceOf(SensitiveContentException.class);

		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("una notificacion previa al tenant se encola sin organizacion")
	void una_notificacion_previa_al_tenant_se_encola_sin_organizacion() {
		// Recuperacion de contrasena: la persona puede no pertenecer todavia a ninguna
		// organizacion, y ponerle una inventada seria peor que dejar el campo nulo.
		given(repository.findByClaveIdempotente("reset:ref-1")).willReturn(Optional.empty());
		given(clock.now()).willReturn(AHORA);
		given(repository.save(any())).willAnswer(invocacion ->
				NotificationFixtures.conId(invocacion.getArgument(0), ENTRY_ID));

		service().enqueue(NotificationEnqueueCommand.of(
				NotificationType.RECUPERACION_PASSWORD, DESTINATARIO, "reset:ref-1", null, "ref-1"));

		ArgumentCaptor<NotificationOutboxEntry> guardada =
				ArgumentCaptor.forClass(NotificationOutboxEntry.class);
		verify(repository).save(guardada.capture());
		assertThat(guardada.getValue().getOrganizationId()).isNull();
		assertThat(guardada.getValue().getPayloadSanitizado()).isEqualTo("{}");
	}
}
