package com.akine.notification.application;

import com.akine.notification.domain.ErrorSanitizer;
import com.akine.notification.domain.NotificationOutboxEntry;
import com.akine.notification.domain.OutboxStatus;
import com.akine.notification.domain.SanitizedPayload;
import com.akine.notification.domain.OutboxWorkerSettings;
import com.akine.notification.domain.port.JitterSource;
import com.akine.notification.domain.port.NotificationClock;
import com.akine.notification.domain.port.NotificationOutboxRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Las transacciones cortas del worker: reclamar, registrar el resultado, recuperar leases.
 *
 * <p><b>Por que estan separadas del envio.</b> El envio de un mail puede tardar segundos y no
 * es transaccional con la base. Si ocurriera dentro de la transaccion de claim, cada tick
 * mantendria filas bloqueadas mientras espera al SMTP, y un timeout dejaria la transaccion
 * abierta el mismo tiempo. Aca las transacciones abren y cierran alrededor del envio, nunca
 * durante.
 *
 * <p>Cada metodo publico es {@code REQUIRES_NEW}: el resultado de una notificacion se confirma
 * por si mismo, sin quedar atado al destino de las demas del lote.
 *
 * <p>Los metodos de registro son <b>idempotentes por estado</b>: si la fila ya no esta
 * PROCESANDO —porque otro worker la resolvio, o porque este esta reprocesando un resultado que
 * ya escribio— no se toca nada y se registra el hecho. Reprocesar no duplica ningun efecto
 * (RF-M26-005, RN-M26-003).
 */
@Service
public class OutboxDispatchService {

	private static final Logger log = LoggerFactory.getLogger(OutboxDispatchService.class);

	private final NotificationOutboxRepositoryPort repository;
	private final NotificationClock clock;
	private final OutboxWorkerSettings settings;
	private final JitterSource jitter;

	public OutboxDispatchService(
			NotificationOutboxRepositoryPort repository,
			NotificationClock clock,
			OutboxWorkerSettings settings,
			JitterSource jitter) {
		this.repository = repository;
		this.clock = clock;
		this.settings = settings;
		this.jitter = jitter;
	}

	/**
	 * Reclama un lote y lo marca PROCESANDO.
	 *
	 * <p>El bloqueo lo hace el repositorio con {@code FOR UPDATE SKIP LOCKED}: dos instancias
	 * del backend —o dos ticks solapados— se llevan lotes distintos en vez de pisarse o de
	 * quedar una esperando a la otra.
	 *
	 * <h2>Una fila ilegible no puede tumbar el lote</h2>
	 *
	 * <p>Releer el payload puede fallar, y hasta que esto se blindo <b>esa excepcion se
	 * propagaba y volteaba el tick entero</b>. La fila quedaba PENDIENTE, la volvia a reclamar
	 * el ciclo siguiente y volvia a volterlo: no era una notificacion perdida sino <b>el outbox
	 * entero clavado para siempre</b>, activaciones y recuperaciones de contrasena incluidas.
	 * Se comprobo contra el stack real —una invitacion basto para que ninguna cuenta nueva
	 * pudiera activarse—.
	 *
	 * <p>Ahora cada fila se convierte por separado y la que no se puede leer se marca FALLIDA
	 * en el acto. Es lo mismo que ya hace {@code OutboxDispatcher} con un template mal formado,
	 * y por el mismo motivo: un payload que no parsea hoy no va a parsear en el proximo intento,
	 * asi que reintentar solo sirve para bloquear a los demas.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<PendingDelivery> reclamarLote() {
		Instant ahora = clock.now();
		List<NotificationOutboxEntry> reclamadas =
				repository.reclamarLote(ahora, settings.tamanoLote());
		List<PendingDelivery> pendientes = new ArrayList<>(reclamadas.size());
		for (NotificationOutboxEntry entry : reclamadas) {
			SanitizedPayload payload;
			try {
				payload = entry.payload();
			} catch (RuntimeException e) {
				log.warn("Payload ilegible en la notificacion id={}: se descarta sin reintentar",
						entry.getId());
				entry.marcarEnProceso(ahora);
				entry.registrarFalloPermanente(ErrorSanitizer.sanitize(e));
				repository.save(entry);
				continue;
			}
			entry.marcarEnProceso(ahora);
			repository.save(entry);
			pendientes.add(new PendingDelivery(
					entry.getId(),
					entry.getTipo(),
					entry.getDestinatario(),
					payload,
					entry.getReferenciaTokenId(),
					entry.getIntentos()));
		}
		if (!pendientes.isEmpty()) {
			log.debug("Notificaciones reclamadas por el worker: {}", pendientes.size());
		}
		return pendientes;
	}

	/**
	 * Devuelve a la cola las filas que quedaron PROCESANDO porque el worker que las tomo murio.
	 *
	 * <p>Sin esto, una instancia que se reinicia en medio de un tick deja notificaciones
	 * atascadas para siempre: nadie las volveria a tomar, porque PROCESANDO no es reclamable.
	 *
	 * @return cuantas se recuperaron
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int recuperarLeasesVencidos() {
		Instant ahora = clock.now();
		Instant limite = ahora.minus(settings.duracionLease());
		List<NotificationOutboxEntry> huerfanas =
				repository.reclamarLeasesVencidos(limite, settings.tamanoLote());
		for (NotificationOutboxEntry entry : huerfanas) {
			entry.recuperarLeaseVencido(ahora, esperaTrasLeaseVencido(entry));
			repository.save(entry);
		}
		if (!huerfanas.isEmpty()) {
			log.warn("Notificaciones recuperadas de un worker caido: {}", huerfanas.size());
		}
		return huerfanas.size();
	}

	/** Entrega exitosa (RF-M26-004: "enviado"). */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void registrarExito(long id) {
		enProceso(id).ifPresent(entry -> {
			entry.marcarEnviada(clock.now());
			repository.save(entry);
			log.info("Notificacion enviada: id={} tipo={} destinatario={}",
					id, entry.getTipo(), ErrorSanitizer.maskEmail(entry.getDestinatario()));
		});
	}

	/**
	 * Fallo transitorio: suma un intento y reprograma con backoff, o agota la notificacion.
	 *
	 * @param motivo motivo crudo; se sanitiza antes de guardarlo
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void registrarFalloTransitorio(long id, String motivo) {
		enProceso(id).ifPresent(entry -> {
			Instant ahora = clock.now();
			Optional<Duration> espera = settings.backoff()
					.proximaEspera(entry.getIntentos() + 1, jitter.next());
			OutboxStatus resultado = entry.registrarFalloTransitorio(ahora, motivo, espera);
			repository.save(entry);
			if (resultado == OutboxStatus.AGOTADA) {
				log.warn("Notificacion agotada tras {} intentos: id={} tipo={} motivo={}",
						entry.getIntentos(), id, entry.getTipo(), entry.getErrorSanitizado());
			} else {
				log.info("Notificacion reprogramada: id={} intento={} proximaEjecucion={} motivo={}",
						id, entry.getIntentos(), entry.getProximaEjecucionEn(),
						entry.getErrorSanitizado());
			}
		});
	}

	/**
	 * Fallo permanente: reintentar daria el mismo resultado (RF-M26-004: "fallido").
	 *
	 * <p>Casos tipicos: direccion invalida, template inexistente, token de un solo uso ya
	 * consumido o vencido (T-11: reenviar un enlace muerto no ayuda a nadie).
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void registrarFalloPermanente(long id, String motivo) {
		enProceso(id).ifPresent(entry -> {
			entry.registrarFalloPermanente(motivo);
			repository.save(entry);
			log.warn("Notificacion fallida sin reintento: id={} tipo={} motivo={}",
					id, entry.getTipo(), entry.getErrorSanitizado());
		});
	}

	/**
	 * Reintento administrativo de una notificacion FALLIDA o AGOTADA.
	 *
	 * <p>Reinicia el contador de intentos y la devuelve a la cola. No reejecuta ningun negocio:
	 * lo unico que se repite es el envio del mail (RF-M26-005).
	 */
	@Transactional
	public boolean reintentarManualmente(long id) {
		Optional<NotificationOutboxEntry> encontrada = repository.findById(id);
		if (encontrada.isEmpty() || !encontrada.get().getEstado().admiteReintentoManual()) {
			return false;
		}
		NotificationOutboxEntry entry = encontrada.get();
		entry.reintentarManualmente(clock.now());
		repository.save(entry);
		log.info("Reintento administrativo de notificacion: id={} tipo={}", id, entry.getTipo());
		return true;
	}

	/**
	 * La fila, solo si sigue PROCESANDO.
	 *
	 * <p>Que no lo este no es un error: significa que otro worker ya la resolvio, o que este
	 * esta reprocesando un resultado ya escrito. En los dos casos lo correcto es no hacer nada,
	 * que es justamente lo que hace que reprocesar sea inocuo.
	 */
	private Optional<NotificationOutboxEntry> enProceso(long id) {
		Optional<NotificationOutboxEntry> encontrada = repository.findById(id);
		if (encontrada.isEmpty()) {
			log.warn("Se intento registrar el resultado de una notificacion inexistente: id={}", id);
			return Optional.empty();
		}
		NotificationOutboxEntry entry = encontrada.get();
		if (entry.getEstado() != OutboxStatus.PROCESANDO) {
			log.debug("Resultado ignorado: la notificacion id={} ya no esta PROCESANDO (estado={})",
					id, entry.getEstado());
			return Optional.empty();
		}
		return Optional.of(entry);
	}

	/** Un lease vencido no consumio un intento, pero igual espera el escalon que corresponde. */
	private Duration esperaTrasLeaseVencido(NotificationOutboxEntry entry) {
		return settings.backoff()
				.proximaEspera(Math.max(entry.getIntentos(), 1), 0.5)
				.orElse(settings.duracionLease());
	}
}
