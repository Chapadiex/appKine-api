package com.akine.organization.domain;

import com.akine.organization.spi.LimitCode;
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
 * Limite cuantitativo que un plan impone (RF-M01-004).
 *
 * <p>{@code limitValue} en {@code null} significa ILIMITADO, y no es lo mismo que no tener
 * fila: la fila declara que el limite fue evaluado y decidido sin tope, mientras que la
 * ausencia significa que ese limite no aplica al plan.
 *
 * <p>El vinculo con el plan se guarda como id y no como {@code @ManyToOne} a proposito: la
 * evaluacion de limites es una consulta puntual por {@code (plan_id, limit_code)} y no
 * necesita navegar el grafo. Una relacion perezosa aca solo aportaria N+1 y accesos fuera de
 * transaccion.
 *
 * <p>Tiene baja logica: quitar un limite de un plan no puede ser un DELETE fisico, porque se
 * perderia que incluia el plan cuando alguien se suscribio.
 */
@Entity
@Table(name = "plan_limit")
public class PlanLimit extends TimestampedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "plan_id", nullable = false, updatable = false)
	private Long planId;

	@Enumerated(EnumType.STRING)
	@Column(name = "limit_code", nullable = false, length = 48, updatable = false)
	private LimitCode limitCode;

	/** {@code null} = ilimitado. */
	@Column(name = "limit_value")
	private Integer limitValue;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected PlanLimit() {
		// Requerido por JPA.
	}

	public PlanLimit(Long planId, LimitCode limitCode, Integer limitValue) {
		this.planId = planId;
		this.limitCode = limitCode;
		this.limitValue = limitValue;
		this.active = true;
	}

	/** Indica si el limite no tiene tope. */
	public boolean isUnlimited() {
		return limitValue == null;
	}

	/**
	 * Indica si un alta mas cabria dentro del limite.
	 *
	 * @param currentUsage cantidad de recursos activos contada DENTRO de la transaccion del
	 *                     alta y despues de bloquear la suscripcion. Contarla antes produce
	 *                     una carrera: dos altas concurrentes leen el mismo valor y las dos
	 *                     pasan el control.
	 */
	public boolean allows(long currentUsage) {
		return isUnlimited() || currentUsage < limitValue;
	}

	public void changeValue(Integer limitValue) {
		this.limitValue = limitValue;
	}

	/**
	 * Baja logica. El unique {@code (plan_id, limit_code)} no discrimina por {@code active},
	 * asi que volver a habilitar este limite se hace con {@link #reactivate()} sobre la fila
	 * existente, nunca insertando una nueva.
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

	public LimitCode getLimitCode() {
		return limitCode;
	}

	public Integer getLimitValue() {
		return limitValue;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}
}
