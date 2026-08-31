package com.akine.scheduling.domain;

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
 * Una reserva de un slot. <b>No es la prestacion.</b>
 *
 * <p>DP-05: Turno, Recepcion y Sesion son tres maquinas de estado independientes, y ninguna
 * transicion administrativa prueba por si sola que la atencion ocurrio. Por eso esta clase no
 * tiene ningun campo clinico y su estado nunca llega a "atendido".
 *
 * <h2>Guarda su propio intervalo, y no lo deriva de la oferta</h2>
 *
 * <p>{@code inicio} y {@code fin} se congelan al reservar. La duracion de una oferta se puede
 * editar despues —M27 lo permite— y un turno ya tomado no cambia de horario porque alguien
 * corrigio el catalogo. Es el mismo criterio que M18 va a aplicar al importe.
 *
 * <h2>El espacio se clava aca y no en la busqueda</h2>
 *
 * <p>El motor de slots de 05.01 solo verifica que exista algun espacio habilitado; elegir cual es
 * de esta etapa y ocurre dentro de la transaccion que crea el turno, bajo el lock de la sede. Fuera
 * de esa transaccion seria una promesa que dos busquedas concurrentes rompen: las dos verian el
 * mismo box libre.
 */
@Entity
@Table(name = "turno")
public class Turno {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "profesional_membership_id")
	private Long profesionalMembershipId;

	@Column(name = "espacio_id")
	private Long espacioId;

	@Column(name = "inicio", nullable = false)
	private Instant inicio;

	@Column(name = "fin", nullable = false)
	private Instant fin;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoTurno estado;

	@Column(name = "idempotency_key", length = 80, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	@Column(name = "reservado_por_cuenta_id", nullable = false, updatable = false)
	private Long reservadoPorCuentaId;

	@Column(name = "reservado_en", nullable = false, updatable = false)
	private Instant reservadoEn;

	@Column(name = "confirmado_en")
	private Instant confirmadoEn;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Turno() {
		// Requerido por JPA.
	}

	public Turno(
			long organizationId,
			long consultorioId,
			long ofertaId,
			long personaId,
			Long profesionalMembershipId,
			Long espacioId,
			Instant inicio,
			Instant fin,
			long reservadoPorCuentaId,
			Instant reservadoEn,
			String idempotencyKey,
			String requestHash) {

		if (!fin.isAfter(inicio)) {
			throw new IllegalArgumentException(
					"Un turno termina despues de empezar: " + inicio + " -> " + fin);
		}
		// El CHECK de V30 dice lo mismo del lado de la base. Se valida en las dos puntas a
		// proposito: la base impide la fila corrupta aunque alguien inserte por fuera de JPA, y
		// esta validacion da un mensaje que nombra el problema en vez de un error de constraint.
		if ((idempotencyKey == null) != (requestHash == null)) {
			throw new IllegalArgumentException(
					"La clave de idempotencia y el hash del pedido viajan juntos o no viajan");
		}

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.ofertaId = ofertaId;
		this.personaId = personaId;
		this.profesionalMembershipId = profesionalMembershipId;
		this.espacioId = espacioId;
		this.inicio = inicio;
		this.fin = fin;
		this.estado = EstadoTurno.RESERVADO;
		this.reservadoPorCuentaId = reservadoPorCuentaId;
		this.reservadoEn = reservadoEn;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
	}

	/**
	 * Confirma la reserva.
	 *
	 * <p><b>Idempotente</b>: confirmar un turno ya confirmado no es un error y no mueve la fecha.
	 * El doble click es el caso normal, no la excepcion, y castigarlo con un 409 obligaria a la
	 * pantalla a distinguir dos situaciones que para el usuario son la misma.
	 */
	public void confirmar(Instant occurredAt) {
		if (estado == EstadoTurno.CONFIRMADO) {
			return;
		}
		this.estado = EstadoTurno.CONFIRMADO;
		this.confirmadoEn = occurredAt;
	}

	/** Un turno vivo ocupa lugar. Los dados de baja llegan en 05.03 y dejan de ocuparlo. */
	public boolean estaVivo() {
		return deletedAt == null;
	}

	/** {@code true} si este turno se cruza con {@code [desde, fin)}. Extremos superiores exclusivos. */
	public boolean seCruzaCon(Instant desde, Instant hasta) {
		return inicio.isBefore(hasta) && desde.isBefore(fin);
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

	public Long getOfertaId() {
		return ofertaId;
	}

	public Long getPersonaId() {
		return personaId;
	}

	public Long getProfesionalMembershipId() {
		return profesionalMembershipId;
	}

	public Long getEspacioId() {
		return espacioId;
	}

	public Instant getInicio() {
		return inicio;
	}

	public Instant getFin() {
		return fin;
	}

	public EstadoTurno getEstado() {
		return estado;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public Long getReservadoPorCuentaId() {
		return reservadoPorCuentaId;
	}

	public Instant getReservadoEn() {
		return reservadoEn;
	}

	public Instant getConfirmadoEn() {
		return confirmadoEn;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public long getVersion() {
		return version;
	}
}
