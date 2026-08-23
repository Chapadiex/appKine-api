package com.akine.notification.application;

import com.akine.notification.NotificationFixtures;
import com.akine.notification.domain.NotificationOutboxEntry;
import com.akine.notification.domain.OutboxStatus;
import com.akine.notification.domain.OutboxWorkerSettings;
import com.akine.notification.domain.port.JitterSource;
import com.akine.notification.domain.port.NotificationClock;
import com.akine.notification.domain.port.NotificationOutboxRepositoryPort;
import com.akine.notification.spi.NotificationType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.akine.notification.NotificationFixtures.AHORA;
import static com.akine.notification.NotificationFixtures.DESTINATARIO;
import static com.akine.notification.NotificationFixtures.ENTRY_ID;
import static com.akine.notification.NotificationFixtures.TOKEN_REF;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Las transacciones cortas del worker: reclamar, registrar el resultado, recuperar leases.
 *
 * <p>Lo que estos tests sostienen: que registrar un resultado sea <b>idempotente por estado</b>.
 * Un worker que reprocesa un resultado ya escrito, o que llega segundo sobre una fila que otro
 * ya resolvio, no puede consumir un intento ni pisar el motivo del fallo anterior
 * (RF-M26-005, RN-M26-003).
 */
@ExtendWith(MockitoExtension.class)
class OutboxDispatchServiceTest {

	@Mock
	private NotificationOutboxRepositoryPort repository;

	@Mock
	private NotificationClock clock;

	private final OutboxWorkerSettings settings = OutboxWorkerSettings.porDefecto();

	/**
	 * Jitter fijo en el centro del rango. Con la fuente inyectada la espera de cada intento es
	 * un valor exacto y el test puede afirmarlo, que es para lo que RetryBackoffPolicy se diseño
	 * pura.
	 */
	private final JitterSource jitter = () -> 0.5;

	private OutboxDispatchService service;

	private OutboxDispatchService service() {
		if (service == null) {
			service = new OutboxDispatchService(repository, clock, settings, jitter);
		}
		return service;
	}

	/** Fila PROCESANDO que ya consumio {@code intentos} envios fallidos. */
	private NotificationOutboxEntry procesandoCon(int intentos) {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.PROCESANDO);
		for (int i = 0; i < intentos; i++) {
			entry.registrarFalloTransitorio(AHORA, "smtp caido", Optional.of(Duration.ofMinutes(1)));
			entry.marcarEnProceso(AHORA);
		}
		return entry;
	}

	@Test
	@DisplayName("reclamar un lote lo marca PROCESANDO y devuelve solo una copia de los datos")
	void reclamar_marca_procesando_y_copia() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.PENDIENTE);
		given(clock.now()).willReturn(AHORA);
		given(repository.reclamarLote(AHORA, settings.tamanoLote())).willReturn(List.of(entry));

		List<PendingDelivery> lote = service().reclamarLote();

		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.PROCESANDO);
		assertThat(entry.getProcesandoDesde()).isEqualTo(AHORA);
		verify(repository).save(entry);

		// El envio ocurre FUERA de esta transaccion: si el worker se llevara la entity, cualquier
		// acceso posterior seria un lazy loading fallido o una escritura accidental.
		assertThat(lote).hasSize(1);
		PendingDelivery pendiente = lote.get(0);
		assertThat(pendiente.id()).isEqualTo(ENTRY_ID);
		assertThat(pendiente.tipo()).isEqualTo(NotificationType.ACTIVACION_CUENTA);
		assertThat(pendiente.destinatario()).isEqualTo(DESTINATARIO);
		assertThat(pendiente.referenciaTokenId()).isEqualTo(TOKEN_REF);
		assertThat(pendiente.intentos()).isZero();
		assertThat(pendiente.payload().asMap()).isEmpty();
	}

	@Test
	@DisplayName("un tick sin nada que hacer no escribe ni devuelve nada")
	void un_tick_vacio_no_escribe() {
		given(clock.now()).willReturn(AHORA);
		given(repository.reclamarLote(AHORA, settings.tamanoLote())).willReturn(List.of());

		assertThat(service().reclamarLote()).isEmpty();
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("las filas huerfanas vuelven a la cola respetando el backoff")
	void las_huerfanas_vuelven_a_la_cola() {
		// Sin esto, una instancia que se reinicia en medio de un tick deja notificaciones
		// atascadas para siempre: PROCESANDO no es reclamable y nadie las volveria a tomar.
		NotificationOutboxEntry huerfana = procesandoCon(1);
		given(clock.now()).willReturn(AHORA);
		given(repository.reclamarLeasesVencidos(
				AHORA.minus(settings.duracionLease()), settings.tamanoLote()))
				.willReturn(List.of(huerfana));

		int recuperadas = service().recuperarLeasesVencidos();

		assertThat(recuperadas).isEqualTo(1);
		assertThat(huerfana.getEstado()).isEqualTo(OutboxStatus.REINTENTABLE);
		// No hubo un envio fallido sino un proceso caido: el contador no se toca.
		assertThat(huerfana.getIntentos()).isEqualTo(1);
		assertThat(huerfana.getProximaEjecucionEn()).isEqualTo(AHORA.plus(Duration.ofMinutes(1)));
		verify(repository).save(huerfana);
	}

	@Test
	@DisplayName("una huerfana sin intentos disponibles igual espera, y espera un lease completo")
	void una_huerfana_agotada_espera_un_lease() {
		// El backoff ya no tiene escalon que ofrecer, pero la fila no puede volver sin espera:
		// una instancia que se reinicia en loop quemaria la cola en cada arranque.
		NotificationOutboxEntry huerfana = procesandoCon(settings.backoff().maxIntentos());
		given(clock.now()).willReturn(AHORA);
		given(repository.reclamarLeasesVencidos(
				AHORA.minus(settings.duracionLease()), settings.tamanoLote()))
				.willReturn(List.of(huerfana));

		service().recuperarLeasesVencidos();

		assertThat(huerfana.getProximaEjecucionEn())
				.isEqualTo(AHORA.plus(settings.duracionLease()));
	}

	@Test
	@DisplayName("sin huerfanas no se escribe nada")
	void sin_huerfanas_no_se_escribe() {
		given(clock.now()).willReturn(AHORA);
		given(repository.reclamarLeasesVencidos(
				AHORA.minus(settings.duracionLease()), settings.tamanoLote()))
				.willReturn(List.of());

		assertThat(service().recuperarLeasesVencidos()).isZero();
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("registrar el exito deja la fila ENVIADA y libera el lease")
	void registrar_el_exito_cierra_la_fila() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.PROCESANDO);
		given(repository.findById(ENTRY_ID)).willReturn(Optional.of(entry));
		given(clock.now()).willReturn(AHORA);

		service().registrarExito(ENTRY_ID);

		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.ENVIADA);
		assertThat(entry.getEnviadoEn()).isEqualTo(AHORA);
		assertThat(entry.getIntentos()).isEqualTo(1);
		verify(repository).save(entry);
	}

	@Test
	@DisplayName("registrar el resultado de una fila que otro worker ya resolvio no toca nada")
	void un_resultado_de_una_fila_ya_resuelta_es_inocuo() {
		// Es lo que hace inocuo reprocesar: si escribiera igual, la fila ENVIADA volveria a la
		// cola y la persona recibiria el mismo mail de nuevo.
		NotificationOutboxEntry entregada = NotificationFixtures.en(OutboxStatus.ENVIADA);
		given(repository.findById(ENTRY_ID)).willReturn(Optional.of(entregada));

		service().registrarExito(ENTRY_ID);
		service().registrarFalloTransitorio(ENTRY_ID, "resultado duplicado");
		service().registrarFalloPermanente(ENTRY_ID, "resultado duplicado");

		assertThat(entregada.getEstado()).isEqualTo(OutboxStatus.ENVIADA);
		assertThat(entregada.getIntentos()).isEqualTo(1);
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("registrar el resultado de una notificacion inexistente no rompe el tick")
	void un_resultado_de_una_fila_inexistente_no_rompe() {
		given(repository.findById(ENTRY_ID)).willReturn(Optional.empty());

		service().registrarExito(ENTRY_ID);
		service().registrarFalloTransitorio(ENTRY_ID, "motivo");
		service().registrarFalloPermanente(ENTRY_ID, "motivo");

		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("un fallo transitorio con intentos disponibles reprograma la fila con backoff")
	void el_fallo_transitorio_reprograma_con_backoff() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.PROCESANDO);
		given(repository.findById(ENTRY_ID)).willReturn(Optional.of(entry));
		given(clock.now()).willReturn(AHORA);

		service().registrarFalloTransitorio(ENTRY_ID, "el relay no responde");

		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.REINTENTABLE);
		assertThat(entry.getIntentos()).isEqualTo(1);
		assertThat(entry.getErrorSanitizado()).isEqualTo("el relay no responde");
		// Primer escalon, 1 minuto. Con jitter 0.5 el factor es exactamente 1: sin la fuente
		// inyectada esto solo podia afirmarse como el rango [48s, 72s].
		assertThat(entry.getProximaEjecucionEn()).isEqualTo(AHORA.plusSeconds(60));
		verify(repository).save(entry);
	}

	@Test
	@DisplayName("el fallo que consume el ultimo intento agota la notificacion")
	void el_ultimo_fallo_agota() {
		NotificationOutboxEntry entry = procesandoCon(settings.backoff().maxIntentos() - 1);
		given(repository.findById(ENTRY_ID)).willReturn(Optional.of(entry));
		given(clock.now()).willReturn(AHORA);

		service().registrarFalloTransitorio(ENTRY_ID, "el relay sigue sin responder");

		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.AGOTADA);
		assertThat(entry.getIntentos()).isEqualTo(settings.backoff().maxIntentos());
		verify(repository).save(entry);
	}

	@Test
	@DisplayName("el motivo que se guarda pasa por el sanitizador")
	void el_motivo_se_guarda_sanitizado() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.PROCESANDO);
		given(repository.findById(ENTRY_ID)).willReturn(Optional.of(entry));

		service().registrarFalloPermanente(ENTRY_ID,
				"rechazo de https://app.akine.test/activar?token=SINTETICO para ana.gomez@ejemplo.test");

		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.FALLIDA);
		assertThat(entry.getErrorSanitizado())
				.doesNotContain("SINTETICO")
				.doesNotContain("app.akine.test")
				.doesNotContain("ana.gomez@ejemplo.test");
	}

	@Test
	@DisplayName("el reintento administrativo devuelve a la cola una fila fallida")
	void el_reintento_administrativo_reabre_una_fallida() {
		NotificationOutboxEntry entry = NotificationFixtures.en(OutboxStatus.FALLIDA);
		given(repository.findById(ENTRY_ID)).willReturn(Optional.of(entry));
		given(clock.now()).willReturn(AHORA);

		assertThat(service().reintentarManualmente(ENTRY_ID)).isTrue();

		assertThat(entry.getEstado()).isEqualTo(OutboxStatus.REINTENTABLE);
		assertThat(entry.getIntentos()).isZero();
		assertThat(entry.getProximaEjecucionEn()).isEqualTo(AHORA);
		verify(repository).save(entry);
	}

	@Test
	@DisplayName("el reintento administrativo no reabre una fila viva ni una ya entregada")
	void el_reintento_administrativo_no_reabre_lo_vivo() {
		// Devuelve false en vez de lanzar: es una accion de una pantalla administrativa, y el
		// caso "la notificacion ya se entrego mientras mirabas la lista" es esperable.
		NotificationOutboxEntry entregada = NotificationFixtures.en(OutboxStatus.ENVIADA);
		given(repository.findById(ENTRY_ID)).willReturn(Optional.of(entregada));

		assertThat(service().reintentarManualmente(ENTRY_ID)).isFalse();

		assertThat(entregada.getEstado()).isEqualTo(OutboxStatus.ENVIADA);
		verify(repository, never()).save(any());
	}

	@Test
	@DisplayName("el reintento administrativo de una notificacion inexistente devuelve false")
	void el_reintento_administrativo_de_lo_inexistente_devuelve_false() {
		given(repository.findById(ENTRY_ID)).willReturn(Optional.empty());

		assertThat(service().reintentarManualmente(ENTRY_ID)).isFalse();
		verify(repository, never()).save(any());
	}
}
