package com.akine.notification.infrastructure;

import com.akine.notification.application.OutboxDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Dispara un tick del outbox cada {@code akine.notification.worker.fixed-delay-ms} (15 s por
 * defecto).
 *
 * <p><b>{@code fixedDelay} y no {@code fixedRate}.</b> {@code fixedRate} arranca el tick
 * siguiente aunque el anterior no haya terminado; con el SMTP lento eso encima ticks hasta que
 * se agota el pool. {@code fixedDelay} espera a que el anterior termine. El
 * {@code SKIP LOCKED} del claim protege igual contra el solapamiento, pero la defensa barata
 * va primero.
 *
 * <p>Toda la logica vive en {@link OutboxDispatcher}, en {@code application}: esta clase solo
 * decide CUANDO, para que el QUE se pueda probar sin scheduler y sin esperar.
 *
 * <p><b>Nunca propaga.</b> Una excepcion que escape de un metodo {@code @Scheduled} apaga la
 * tarea entera: el worker deja de correr en silencio y las notificaciones se acumulan sin que
 * nadie se entere. Se registra y se sigue en el proximo tick.
 */
@Component
@ConditionalOnProperty(
		name = "akine.notification.worker.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxWorker {

	private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

	private final OutboxDispatcher dispatcher;

	public OutboxWorker(OutboxDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	@Scheduled(
			fixedDelayString = "${akine.notification.worker.fixed-delay-ms:15000}",
			initialDelayString = "${akine.notification.worker.initial-delay-ms:20000}")
	public void tick() {
		try {
			int procesadas = dispatcher.runOnce();
			if (procesadas > 0) {
				log.debug("Tick del outbox: {} notificaciones procesadas", procesadas);
			}
		} catch (RuntimeException e) {
			log.error("El tick del outbox fallo; se reintenta en el proximo ciclo", e);
		}
	}
}
