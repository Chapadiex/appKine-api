package com.akine.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Fila del historico de la suscripcion (RN-M01-002, RNF-M01-006).
 *
 * <p><b>Append-only.</b> Una vez escrita no se actualiza ni se borra, y por eso la clase no
 * tiene un solo setter: la inmutabilidad no se declara en un comentario, se hace imposible de
 * violar. El repositorio hace lo mismo del lado de la persistencia, exponiendo lectura y
 * {@code save} y nada mas.
 *
 * <p>Registra tanto las transiciones de estado como los cambios de plan. En un cambio de plan
 * {@code fromStatus} y {@code toStatus} son ambos ACTIVA y lo que cambia son los ids de plan:
 * asi el historico responde "que le paso a esta suscripcion" con una sola consulta.
 */
@Entity
@Table(name = "subscription_transition")
public class SubscriptionTransition {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "subscription_id", nullable = false, updatable = false)
	private Long subscriptionId;

	/** {@code null} = alta inicial de la suscripcion. */
	@Enumerated(EnumType.STRING)
	@Column(name = "from_status", length = 16, updatable = false)
	private SubscriptionStatus fromStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "to_status", nullable = false, length = 16, updatable = false)
	private SubscriptionStatus toStatus;

	@Column(name = "from_plan_id", updatable = false)
	private Long fromPlanId;

	@Column(name = "to_plan_id", updatable = false)
	private Long toPlanId;

	/** Obligatorio al suspender o cancelar; lo exige la capa de aplicacion, no la base. */
	@Column(name = "reason", length = 500, updatable = false)
	private String reason;

	/** {@code null} = sistema. Referencia logica a la cuenta, sin FK fisica. */
	@Column(name = "actor_account_id", updatable = false)
	private Long actorAccountId;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	protected SubscriptionTransition() {
		// Requerido por JPA.
	}

	public SubscriptionTransition(
			Long organizationId,
			Long subscriptionId,
			SubscriptionStatus fromStatus,
			SubscriptionStatus toStatus,
			Long fromPlanId,
			Long toPlanId,
			String reason,
			Long actorAccountId,
			Instant occurredAt) {
		this.organizationId = organizationId;
		this.subscriptionId = subscriptionId;
		this.fromStatus = fromStatus;
		this.toStatus = toStatus;
		this.fromPlanId = fromPlanId;
		this.toPlanId = toPlanId;
		this.reason = reason;
		this.actorAccountId = actorAccountId;
		this.occurredAt = occurredAt;
	}

	/** Indica si la fila registra un cambio de plan y no solo un cambio de estado. */
	public boolean isPlanChange() {
		return toPlanId != null && !toPlanId.equals(fromPlanId);
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getSubscriptionId() {
		return subscriptionId;
	}

	public SubscriptionStatus getFromStatus() {
		return fromStatus;
	}

	public SubscriptionStatus getToStatus() {
		return toStatus;
	}

	public Long getFromPlanId() {
		return fromPlanId;
	}

	public Long getToPlanId() {
		return toPlanId;
	}

	public String getReason() {
		return reason;
	}

	public Long getActorAccountId() {
		return actorAccountId;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}
}
