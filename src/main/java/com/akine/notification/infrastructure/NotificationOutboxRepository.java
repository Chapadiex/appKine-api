package com.akine.notification.infrastructure;

import com.akine.notification.domain.NotificationOutboxEntry;
import com.akine.notification.domain.port.NotificationOutboxRepositoryPort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Acceso a {@code notification_outbox}. Extiende el puerto plano del dominio: no hay clase
 * adaptadora en el medio, Spring Data deriva o implementa los metodos declarados alli.
 */
public interface NotificationOutboxRepository
		extends JpaRepository<NotificationOutboxEntry, Long>, NotificationOutboxRepositoryPort {

	Optional<NotificationOutboxEntry> findByClaveIdempotente(String claveIdempotente);

	/**
	 * Reclama el lote del worker con {@code FOR UPDATE SKIP LOCKED} (MySQL 8.4).
	 *
	 * <p><b>Por que SKIP LOCKED.</b> Es exactamente el mecanismo que existe para repartir una
	 * cola entre varios consumidores. Sin el hay dos caminos y los dos son malos: con un
	 * {@code SELECT} normal, dos workers —dos instancias, o dos ticks solapados de una sola—
	 * leen el mismo lote, lo marcan PROCESANDO los dos y mandan el mismo mail dos veces; con
	 * un {@code FOR UPDATE} a secas no hay duplicado, pero el segundo worker queda BLOQUEADO
	 * esperando a que el primero termine de hablar con el SMTP, y el paralelismo se convierte
	 * en una fila india con transacciones largas. {@code SKIP LOCKED} hace que el segundo
	 * worker saltee lo que ya tiene otro y se lleve las siguientes filas: una fila, un worker,
	 * sin esperas.
	 *
	 * <p>Query nativa porque JPQL no sabe expresar {@code SKIP LOCKED}: el {@code @Lock}
	 * de Spring Data llega hasta {@code FOR UPDATE}, que es justo lo que no alcanza.
	 *
	 * <p>Se ordena por {@code proxima_ejecucion_en} —lo mas atrasado primero— y se acota con
	 * {@code LIMIT}: un tick que intentara vaciar la cola entera mantendria bloqueadas
	 * demasiadas filas durante demasiado tiempo.
	 *
	 * <p>Solo tiene sentido dentro de una transaccion: fuera de una, el bloqueo se libera al
	 * instante y la exclusion desaparece.
	 */
	@Override
	@Query(value = """
			SELECT * FROM notification_outbox
			 WHERE estado IN ('PENDIENTE', 'REINTENTABLE')
			   AND proxima_ejecucion_en <= :ahora
			 ORDER BY proxima_ejecucion_en
			 LIMIT :limite
			 FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<NotificationOutboxEntry> reclamarLote(
			@Param("ahora") Instant ahora, @Param("limite") int limite);

	/**
	 * Filas que quedaron PROCESANDO con el lease vencido: el worker que las tomo no volvio.
	 *
	 * <p>Tambien con {@code SKIP LOCKED}, por el mismo motivo elevado al cuadrado: la
	 * recuperacion no puede quedar esperando a un worker vivo que esta trabajando
	 * legitimamente sobre una de esas filas.
	 */
	@Override
	@Query(value = """
			SELECT * FROM notification_outbox
			 WHERE estado = 'PROCESANDO'
			   AND procesando_desde < :limite
			 ORDER BY procesando_desde
			 LIMIT :maximo
			 FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<NotificationOutboxEntry> reclamarLeasesVencidos(
			@Param("limite") Instant limite, @Param("maximo") int maximo);
}
