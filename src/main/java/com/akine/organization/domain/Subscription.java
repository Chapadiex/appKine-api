package com.akine.organization.domain;

import com.akine.organization.domain.exception.SubscriptionSuspendedException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Suscripcion vigente de una organizacion (RF-M01-001, RF-M01-003).
 *
 * <p>Hay exactamente UNA por organizacion, mutable, y la historia vive en
 * {@link SubscriptionTransition}. La alternativa —una fila por periodo, cerrando la anterior—
 * exige preguntar "cual esta vigente" en cada consulta y deja la puerta abierta a dos filas
 * vigentes a la vez.
 *
 * <p>Ademas de representar el estado, esta fila es el PUNTO DE SERIALIZACION de la evaluacion
 * de limites: el gate la bloquea con {@code SELECT ... FOR UPDATE} antes de contar, para que
 * dos altas concurrentes no pasen las dos el mismo control. El bloqueo afecta a un solo
 * tenant y la seccion critica es corta.
 */
@Entity
@Table(name = "subscription")
public class Subscription extends TimestampedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "plan_id", nullable = false)
	private Long planId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private SubscriptionStatus status;

	@Column(name = "started_at", nullable = false, updatable = false)
	private Instant startedAt;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	/** Locking optimista: dos transiciones concurrentes no pueden ganar las dos. */
	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Subscription() {
		// Requerido por JPA.
	}

	/**
	 * Da de alta la suscripcion. Nace siempre {@link SubscriptionStatus#ACTIVA}: es la unica
	 * forma de nacer que admite la maquina de estados.
	 */
	public Subscription(Long organizationId, Long planId, Instant startedAt) {
		this.organizationId = organizationId;
		this.planId = planId;
		this.status = SubscriptionStateMachine.ESTADO_INICIAL;
		this.startedAt = startedAt;
		this.active = true;
	}

	/**
	 * Aplica una transicion de estado, validandola contra la maquina de estados.
	 *
	 * <p>La validacion esta ACA y no en el servicio para que no exista ningun camino de
	 * escritura que la saltee: cualquier codigo que quiera mover el estado pasa por este
	 * metodo.
	 *
	 * @throws com.akine.organization.domain.exception.InvalidSubscriptionTransitionException
	 *         si la transicion no esta permitida, incluido el caso {@code from == to}
	 */
	public void transitionTo(SubscriptionStatus nuevoEstado) {
		SubscriptionStateMachine.assertTransitionAllowed(this.status, nuevoEstado);
		this.status = nuevoEstado;
	}

	/**
	 * Cambia el plan contratado.
	 *
	 * <p>No es una transicion de estado: solo se permite con la suscripcion ACTIVA, y un
	 * downgrade NUNCA toca datos existentes (RN-M01-004). El efecto es prospectivo: la
	 * proxima alta que exceda el limite nuevo se rechaza, y lo que ya existe sigue operativo
	 * y consultable.
	 *
	 * @throws SubscriptionSuspendedException si la suscripcion no esta ACTIVA, sea porque
	 *         esta SUSPENDIDA o porque esta CANCELADA; el mensaje nombra cual de las dos
	 */
	public void changePlan(Long nuevoPlanId) {
		if (this.status != SubscriptionStatus.ACTIVA) {
			throw new SubscriptionSuspendedException(this.organizationId, this.status);
		}
		this.planId = nuevoPlanId;
	}

	/** Indica si la suscripcion habilita mutaciones de negocio. */
	public boolean allowsBusinessMutations() {
		return active && status == SubscriptionStatus.ACTIVA;
	}

	/**
	 * Estado operativo del tenant derivado de esta suscripcion.
	 *
	 * @param organizationActive si la organizacion misma sigue activa; una organizacion dada
	 *                           de baja gana sobre cualquier estado de suscripcion
	 */
	public OperationalStatus operationalStatus(boolean organizationActive) {
		if (!organizationActive) {
			return OperationalStatus.BAJA;
		}
		return switch (status) {
			case ACTIVA -> OperationalStatus.ACTIVA;
			case SUSPENDIDA -> OperationalStatus.SUSPENDIDA;
			case CANCELADA -> OperationalStatus.CANCELADA;
		};
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getPlanId() {
		return planId;
	}

	public SubscriptionStatus getStatus() {
		return status;
	}

	public Instant getStartedAt() {
		return startedAt;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public long getVersion() {
		return version;
	}
}
