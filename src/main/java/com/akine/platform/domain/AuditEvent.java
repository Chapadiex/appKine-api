package com.akine.platform.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Fila de la auditoria de la plataforma (RNF-M01-006, RNF-M01-007).
 *
 * <p><b>Inmutable y append-only.</b> Sin setters, sin {@code updated_at}, sin baja logica y
 * sin {@code version}: una fila de auditoria que se puede editar no es auditoria. El
 * repositorio hace cumplir lo mismo del lado de la persistencia exponiendo lectura y
 * {@code save} unicamente; 01.03 agrega ademas el enforcement en la base con un trigger
 * {@code SIGNAL} y su test.
 *
 * <p>Se escribe DENTRO de la transaccion de negocio: si el INSERT falla, la operacion no se
 * confirma. Ver {@code platform.spi.audit.AuditTrail}.
 *
 * <p>{@code organizationId} admite {@code null} SOLO para eventos de plataforma sin tenant.
 * No hay FK hacia {@code organization} a proposito: la auditoria tiene que poder sobrevivir a
 * cualquier operacion sobre el tenant que audita.
 *
 * <p>{@code details} es JSON libre y JAMAS contiene secretos ni contenido clinico.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", updatable = false)
	private Long consultorioId;

	/** {@code null} = sistema. Referencia logica a la cuenta, sin FK fisica. */
	@Column(name = "actor_account_id", updatable = false)
	private Long actorAccountId;

	@Column(name = "event_type", nullable = false, length = 64, updatable = false)
	private String eventType;

	@Column(name = "entity_type", nullable = false, length = 64, updatable = false)
	private String entityType;

	@Column(name = "entity_id", updatable = false)
	private Long entityId;

	@Column(name = "previous_state", length = 64, updatable = false)
	private String previousState;

	@Column(name = "new_state", length = 64, updatable = false)
	private String newState;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "details", updatable = false)
	private String details;

	@Column(name = "reason", length = 500, updatable = false)
	private String reason;

	@Column(name = "correlation_id", length = 64, updatable = false)
	private String correlationId;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	protected AuditEvent() {
		// Requerido por JPA.
	}

	public AuditEvent(
			Long organizationId,
			Long consultorioId,
			Long actorAccountId,
			String eventType,
			String entityType,
			Long entityId,
			String previousState,
			String newState,
			String details,
			String reason,
			String correlationId,
			Instant occurredAt) {
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.actorAccountId = actorAccountId;
		this.eventType = eventType;
		this.entityType = entityType;
		this.entityId = entityId;
		this.previousState = previousState;
		this.newState = newState;
		this.details = details;
		this.reason = reason;
		this.correlationId = correlationId;
		this.occurredAt = occurredAt;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Long getActorAccountId() {
		return actorAccountId;
	}

	public String getEventType() {
		return eventType;
	}

	public String getEntityType() {
		return entityType;
	}

	public Long getEntityId() {
		return entityId;
	}

	public String getPreviousState() {
		return previousState;
	}

	public String getNewState() {
		return newState;
	}

	public String getDetails() {
		return details;
	}

	public String getReason() {
		return reason;
	}

	public String getCorrelationId() {
		return correlationId;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}
}
