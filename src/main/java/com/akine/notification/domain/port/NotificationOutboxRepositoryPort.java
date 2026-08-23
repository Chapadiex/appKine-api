package com.akine.notification.domain.port;

import com.akine.notification.domain.NotificationOutboxEntry;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a la tabla {@code notification_outbox}.
 */
public interface NotificationOutboxRepositoryPort {

	NotificationOutboxEntry save(NotificationOutboxEntry entry);

	Optional<NotificationOutboxEntry> findById(Long id);

	/** La lectura que hace real la idempotencia del productor (RF-M26-005). */
	Optional<NotificationOutboxEntry> findByClaveIdempotente(String claveIdempotente);

	/**
	 * Reclama un lote de notificaciones listas para enviar, BLOQUEANDO las filas con
	 * {@code SELECT ... FOR UPDATE SKIP LOCKED}.
	 *
	 * <p><b>Por que SKIP LOCKED y no un SELECT normal.</b> Con dos instancias del backend —o
	 * simplemente con dos ticks del scheduler solapados— dos workers leen el mismo lote, los
	 * dos lo marcan PROCESANDO y los dos mandan el mismo mail. Un {@code FOR UPDATE} a secas
	 * tampoco alcanza: el segundo worker no duplicaria, pero quedaria BLOQUEADO esperando a
	 * que el primero termine, convirtiendo el paralelismo en una fila india. {@code SKIP
	 * LOCKED} (MySQL 8) hace exactamente lo que hace falta: el segundo worker saltea las filas
	 * que ya tiene otro y se lleva las siguientes. Cada fila la procesa un solo worker y las
	 * instancias escalan de verdad.
	 *
	 * <p>El lote se ordena por {@code proxima_ejecucion_en} para que lo mas atrasado salga
	 * primero, y se acota con {@code LIMIT}: un tick que intente vaciar toda la cola mantiene
	 * abierta una transaccion larga con muchas filas bloqueadas.
	 *
	 * <p>Debe invocarse dentro de una transaccion: fuera de una, el bloqueo se libera al
	 * instante y la garantia desaparece.
	 */
	List<NotificationOutboxEntry> reclamarLote(Instant ahora, int limite);

	/**
	 * Filas que quedaron PROCESANDO con el lease vencido, para recuperarlas.
	 *
	 * <p>Tambien con {@code SKIP LOCKED}: la recuperacion no puede quedar esperando a un
	 * worker vivo que esta legitimamente trabajando sobre una de esas filas.
	 */
	List<NotificationOutboxEntry> reclamarLeasesVencidos(Instant limite, int maximo);
}
