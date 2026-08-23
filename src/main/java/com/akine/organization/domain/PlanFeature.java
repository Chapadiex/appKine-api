package com.akine.organization.domain;

import com.akine.organization.spi.FeatureCode;
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
 * Funcionalidad habilitada por un plan (RF-M01-004).
 *
 * <p>La habilitacion se representa por PRESENCIA de una fila activa, no por un booleano:
 * agregar una feature nueva no cambia el esquema y ningun plan viejo la hereda por omision.
 *
 * <p>Tiene baja logica por el mismo motivo que {@link PlanLimit}: quitarle una feature a un
 * plan borraria el rastro de que alguna vez la incluyo.
 */
@Entity
@Table(name = "plan_feature")
public class PlanFeature extends TimestampedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "plan_id", nullable = false, updatable = false)
	private Long planId;

	@Enumerated(EnumType.STRING)
	@Column(name = "feature_code", nullable = false, length = 48, updatable = false)
	private FeatureCode featureCode;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected PlanFeature() {
		// Requerido por JPA.
	}

	public PlanFeature(Long planId, FeatureCode featureCode) {
		this.planId = planId;
		this.featureCode = featureCode;
		this.active = true;
	}

	/**
	 * Baja logica. Como en {@link PlanLimit}, el unique no discrimina por {@code active}:
	 * rehabilitar la feature es {@link #reactivate()}, no una fila nueva.
	 */
	public void deactivate(Instant occurredAt) {
		this.active = false;
		this.deletedAt = occurredAt;
	}

	public void reactivate() {
		this.active = true;
		this.deletedAt = null;
	}

	public Long getId() {
		return id;
	}

	public Long getPlanId() {
		return planId;
	}

	public FeatureCode getFeatureCode() {
		return featureCode;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}
}
