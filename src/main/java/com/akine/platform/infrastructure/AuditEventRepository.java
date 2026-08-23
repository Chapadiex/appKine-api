package com.akine.platform.infrastructure;

import com.akine.platform.domain.AuditEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;

import java.time.Instant;
import java.util.Optional;

/**
 * Acceso a {@code audit_event}. <b>Solo lectura y alta.</b>
 *
 * <p>Extiende {@link Repository} y no {@code JpaRepository} por la misma razon que
 * {@code SubscriptionTransitionRepository}: heredar {@code delete} y {@code deleteAll} sobre
 * la tabla de auditoria haria que "append-only" dependiera de que nadie los llame. Aca esos
 * metodos no existen.
 *
 * <p>Es la contracara del contrato {@code platform.spi.audit.AuditTrail}: el {@code save} se
 * ejecuta DENTRO de la transaccion de negocio, y si falla, la operacion auditada no se
 * confirma.
 *
 * <p>Las consultas paginadas y sus permisos ({@code auditoria:read},
 * {@code auditoria:read-clinica}) los agrega 01.03; aca solo quedan las lecturas por tenant
 * que hacen falta para verificar la escritura.
 */
public interface AuditEventRepository extends Repository<AuditEvent, Long> {

	/** Unica escritura permitida: agregar una fila. */
	AuditEvent save(AuditEvent event);

	Optional<AuditEvent> findById(Long id);

	/** Que le paso a una entidad. Usa {@code ix_audit_event_entity}. */
	Page<AuditEvent> findAllByOrganizationIdAndEntityTypeAndEntityIdOrderByOccurredAtDesc(
			Long organizationId, String entityType, Long entityId, Pageable pageable);

	/** Que hizo un actor dentro de un tenant. Usa {@code ix_audit_event_actor}. */
	Page<AuditEvent> findAllByOrganizationIdAndActorAccountIdOrderByOccurredAtDesc(
			Long organizationId, Long actorAccountId, Pageable pageable);

	/** Ventana temporal de un tenant, para investigar un incidente acotado. */
	Page<AuditEvent> findAllByOrganizationIdAndOccurredAtBetweenOrderByOccurredAtDesc(
			Long organizationId, Instant desde, Instant hasta, Pageable pageable);
}
