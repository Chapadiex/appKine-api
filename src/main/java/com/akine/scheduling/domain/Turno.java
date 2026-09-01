package com.akine.scheduling.domain;

import com.akine.scheduling.domain.exception.TransicionDeTurnoNoPermitidaException;

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

	@Column(name = "motivo_cancelacion", length = 300)
	private String motivoCancelacion;

	@Column(name = "cancelado_en")
	private Instant canceladoEn;

	@Column(name = "cancelado_por_cuenta_id")
	private Long canceladoPorCuentaId;

	@Column(name = "ausente_en")
	private Instant ausenteEn;

	@Column(name = "reprogramado_en")
	private Instant reprogramadoEn;

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

	/**
	 * Cancela la reserva con motivo declarado. <b>Libera el lugar y conserva la fila.</b>
	 *
	 * <p>RN-M12-002 y DP-04: cancelar no elimina fisicamente, exige motivo y queda auditado. La
	 * baja logica es lo que libera el lugar —las consultas de solapamiento filtran por
	 * {@code deletedAt IS NULL}— asi que el hueco vuelve a estar disponible sin borrar nada.
	 *
	 * <p><b>No es idempotente, a diferencia de {@link #confirmar}.</b> Cancelar dos veces no es un
	 * doble click sin consecuencias: entre las dos llamadas el lugar pudo haber sido tomado por
	 * otro paciente, y devolver 200 en silencio le haria creer al operador que la segunda
	 * cancelacion —quiza con otro motivo— quedo registrada.
	 *
	 * @throws TransicionDeTurnoNoPermitidaException si el turno ya termino su ciclo o ya empezo
	 */
	public void cancelar(String motivo, long cuentaId, Instant occurredAt) {
		exigirEstadoTransitable("ya esta " + estado.name().toLowerCase());
		if (!inicio.isAfter(occurredAt)) {
			// DP-04: los turnos pasados o ya ejecutados permanecen inalterables. Un turno que ya
			// paso y no ocurrio no se cancela: se marca AUSENTE, que es un hecho distinto.
			throw new TransicionDeTurnoNoPermitidaException(
					id, "ya empezo; un turno pasado se marca ausente, no se cancela");
		}
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("El motivo de cancelacion es obligatorio (DP-04)");
		}
		this.estado = EstadoTurno.CANCELADO;
		this.motivoCancelacion = motivo.strip();
		this.canceladoEn = occurredAt;
		this.canceladoPorCuentaId = cuentaId;
		this.deletedAt = occurredAt;
	}

	/**
	 * Registra que el paciente no vino. <b>No libera el lugar</b> y no borra nada.
	 *
	 * <p>DP-04, que deroga explicitamente el comportamiento del documento historico de 2019: una
	 * ausencia <b>nunca</b> elimina el turno y nunca altera los siguientes. El lugar sigue ocupado
	 * porque la hora se consumio igual —el profesional estuvo ahi— y liberarlo haria que la agenda
	 * del pasado mintiera sobre lo que ocurrio.
	 *
	 * @throws TransicionDeTurnoNoPermitidaException si el turno todavia no empezo o ya cerro
	 */
	public void marcarAusente(Instant occurredAt) {
		exigirEstadoTransitable("ya esta " + estado.name().toLowerCase());
		if (inicio.isAfter(occurredAt)) {
			throw new TransicionDeTurnoNoPermitidaException(
					id, "todavia no empezo; una ausencia solo se registra despues de la hora");
		}
		this.estado = EstadoTurno.AUSENTE;
		this.ausenteEn = occurredAt;
	}

	/**
	 * Mueve la reserva a otro intervalo. <b>Es el mismo turno</b>: conserva id, persona e historial.
	 *
	 * <p>DP-04 exige que cada Turno conserve identidad e historial propios, y ademas la Sesion de
	 * M14 cuelga de {@code turno_id} con un unique: un reemplazo por par cancelado/nuevo cortaria
	 * esa cadena. La trazabilidad la da {@code turno_evento}, que guarda el intervalo anterior.
	 *
	 * <p><b>Vuelve a {@code RESERVADO} aunque estuviera confirmado</b>, y no es un descuido: lo que
	 * el paciente confirmo fue OTRO horario. Dejarlo confirmado convertiria la confirmacion en una
	 * marca sin significado.
	 *
	 * @throws TransicionDeTurnoNoPermitidaException si el turno ya cerro su ciclo o ya empezo
	 */
	public void reprogramar(
			Instant nuevoInicio,
			Instant nuevoFin,
			Long nuevoProfesionalId,
			Long nuevoEspacioId,
			Instant occurredAt) {

		exigirEstadoTransitable("ya esta " + estado.name().toLowerCase());
		if (!inicio.isAfter(occurredAt)) {
			throw new TransicionDeTurnoNoPermitidaException(id, "ya empezo y no se puede mover");
		}
		if (!nuevoInicio.isAfter(occurredAt)) {
			throw new TransicionDeTurnoNoPermitidaException(id, "el horario nuevo esta en el pasado");
		}
		if (!nuevoFin.isAfter(nuevoInicio)) {
			throw new IllegalArgumentException(
					"Un turno termina despues de empezar: " + nuevoInicio + " -> " + nuevoFin);
		}
		this.inicio = nuevoInicio;
		this.fin = nuevoFin;
		this.profesionalMembershipId = nuevoProfesionalId;
		this.espacioId = nuevoEspacioId;
		this.estado = EstadoTurno.RESERVADO;
		this.confirmadoEn = null;
		this.reprogramadoEn = occurredAt;
	}

	private void exigirEstadoTransitable(String motivo) {
		if (!estado.admiteTransicion()) {
			throw new TransicionDeTurnoNoPermitidaException(id, motivo);
		}
	}

	/** Un turno vivo ocupa lugar. Un cancelado no: su baja logica es lo que libera el hueco. */
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

	public String getMotivoCancelacion() {
		return motivoCancelacion;
	}

	public Instant getCanceladoEn() {
		return canceladoEn;
	}

	public Long getCanceladoPorCuentaId() {
		return canceladoPorCuentaId;
	}

	public Instant getAusenteEn() {
		return ausenteEn;
	}

	public Instant getReprogramadoEn() {
		return reprogramadoEn;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public long getVersion() {
		return version;
	}
}
