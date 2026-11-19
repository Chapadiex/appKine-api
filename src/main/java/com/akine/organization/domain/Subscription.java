package com.akine.organization.domain;

import com.akine.organization.domain.exception.CobroNoAdmitidoException;
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

	/** Fin de la prueba gratis; solo en {@link SubscriptionStatus#PRUEBA} (V28, 18/11/2026). */
	@Column(name = "prueba_hasta")
	private Instant pruebaHasta;

	/**
	 * Hasta cuando esta pagada la suscripcion (V30, 19/11/2026). {@code null} = sin control de
	 * cobro: el centro opera como siempre. Ver {@link SituacionDeCobro}.
	 */
	@Column(name = "pagado_hasta")
	private Instant pagadoHasta;

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
	 * Da de alta una suscripcion en prueba gratis hasta {@code pruebaHasta}. La prueba vencida
	 * no cambia de estado sola: se LEE como suspendida (ver {@link #estadoEfectivo}).
	 */
	public static Subscription enPrueba(Long organizationId, Long planId, Instant startedAt,
			Instant pruebaHasta) {
		if (pruebaHasta == null || !pruebaHasta.isAfter(startedAt)) {
			throw new IllegalArgumentException("La prueba tiene que terminar despues de empezar");
		}
		Subscription s = new Subscription(organizationId, planId, startedAt);
		s.status = SubscriptionStatus.PRUEBA;
		s.pruebaHasta = pruebaHasta;
		return s;
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
		// Salir de la prueba borra su fin: el CHECK de V28 lo exige solo en PRUEBA.
		if (nuevoEstado != SubscriptionStatus.PRUEBA) {
			this.pruebaHasta = null;
		}
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
		if (this.status != SubscriptionStatus.ACTIVA && this.status != SubscriptionStatus.PRUEBA) {
			throw new SubscriptionSuspendedException(this.organizationId, this.status);
		}
		this.planId = nuevoPlanId;
	}

	/**
	 * Registra un cobro que extiende el periodo pagado hasta {@code hasta} (19/11/2026). Una
	 * prueba pasa a ACTIVA por la maquina de estados; SUSPENDIDA o CANCELADA guardadas no admiten
	 * cobro: primero se reactivan por la via de siempre.
	 *
	 * @throws CobroNoAdmitidoException si la suscripcion no esta en PRUEBA ni ACTIVA
	 */
	public void registrarCobro(Instant hasta) {
		if (status != SubscriptionStatus.ACTIVA && status != SubscriptionStatus.PRUEBA) {
			throw new CobroNoAdmitidoException(organizationId, status);
		}
		if (hasta == null || (pagadoHasta != null && !hasta.isAfter(pagadoHasta))) {
			throw new IllegalArgumentException("El cobro tiene que extender el periodo pagado");
		}
		if (status == SubscriptionStatus.PRUEBA) {
			transitionTo(SubscriptionStatus.ACTIVA);
		}
		this.pagadoHasta = hasta;
	}

	/** Indica si la suscripcion habilita mutaciones de negocio. */
	public boolean allowsBusinessMutations() {
		return active && estadoEfectivo(Instant.now()) == SubscriptionStatus.ACTIVA;
	}

	/** {@link #estadoEfectivo(SubscriptionStatus, Instant, Instant, Instant)} de esta suscripcion. */
	public SubscriptionStatus estadoEfectivo(Instant ahora) {
		return estadoEfectivo(status, pruebaHasta, pagadoHasta, ahora);
	}

	/**
	 * El estado efectivo teniendo en cuenta tambien el cobro (19/11/2026): una ACTIVA pagada
	 * opera como ACTIVA hasta el fin de la gracia y como SUSPENDIDA (solo lectura) despues. Una
	 * SUSPENDIDA o CANCELADA guardada manda sobre el cobro, y sin {@code pagadoHasta} no hay
	 * control. El cierre del acceso no es un estado de suscripcion: lo agrega
	 * {@link #estadoOperativo}.
	 */
	public static SubscriptionStatus estadoEfectivo(SubscriptionStatus status, Instant pruebaHasta,
			Instant pagadoHasta, Instant ahora) {
		if (status == SubscriptionStatus.ACTIVA && SituacionDeCobro.de(pagadoHasta, ahora).soloLectura()) {
			return SubscriptionStatus.SUSPENDIDA;
		}
		return estadoEfectivo(status, pruebaHasta, ahora);
	}

	/**
	 * Estado operativo completo, con la prueba y el cobro: el de
	 * {@link #operationalStatus(SubscriptionStatus, boolean)} sobre el estado efectivo, salvo una
	 * ACTIVA cuyo cobro paso el cierre, que es {@link OperationalStatus#SIN_ACCESO}.
	 */
	public static OperationalStatus estadoOperativo(SubscriptionStatus status, Instant pruebaHasta,
			Instant pagadoHasta, Instant ahora, boolean organizationActive) {
		if (organizationActive && status == SubscriptionStatus.ACTIVA
				&& SituacionDeCobro.de(pagadoHasta, ahora) == SituacionDeCobro.SIN_ACCESO) {
			return OperationalStatus.SIN_ACCESO;
		}
		return operationalStatus(estadoEfectivo(status, pruebaHasta, pagadoHasta, ahora), organizationActive);
	}

	/**
	 * El estado con el que opera el centro: una prueba vigente opera como ACTIVA y una vencida
	 * como SUSPENDIDA (lee, no escribe). Se calcula al leer para no depender de una tarea que
	 * mueva el estado a tiempo.
	 */
	public static SubscriptionStatus estadoEfectivo(SubscriptionStatus status, Instant pruebaHasta,
			Instant ahora) {
		if (status != SubscriptionStatus.PRUEBA) {
			return status;
		}
		return pruebaHasta != null && ahora.isBefore(pruebaHasta)
				? SubscriptionStatus.ACTIVA
				: SubscriptionStatus.SUSPENDIDA;
	}

	/**
	 * Estado operativo del tenant derivado de esta suscripcion.
	 *
	 * @param organizationActive si la organizacion misma sigue activa; una organizacion dada
	 *                           de baja gana sobre cualquier estado de suscripcion
	 */
	public OperationalStatus operationalStatus(boolean organizationActive) {
		return estadoOperativo(status, pruebaHasta, pagadoHasta, Instant.now(), organizationActive);
	}

	/**
	 * La misma derivacion que {@link #operationalStatus(boolean)}, a partir del estado leido sin
	 * la entidad (la resolucion de contexto lee solo esa columna, docs/carga.md §13).
	 */
	public static OperationalStatus operationalStatus(SubscriptionStatus status, boolean organizationActive) {
		if (!organizationActive) {
			return OperationalStatus.BAJA;
		}
		return switch (status) {
			// Quien pasa un PRUEBA crudo no aplico estadoEfectivo: se la trata como vigente.
			case ACTIVA, PRUEBA -> OperationalStatus.ACTIVA;
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

	public Instant getPruebaHasta() {
		return pruebaHasta;
	}

	public Instant getPagadoHasta() {
		return pagadoHasta;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public long getVersion() {
		return version;
	}
}
