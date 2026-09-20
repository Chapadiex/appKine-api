package com.akine.activity.domain;

import com.akine.activity.domain.exception.TransicionDeClaseNoPermitidaException;
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
 * Un evento grupal unico de agenda (M28, RF-M28-001).
 *
 * <h2>Una clase NO es N turnos</h2>
 *
 * <p>CA-M28-001-06: la clase existe <b>una sola vez</b> cualquiera sea el numero de participantes.
 * Ninguna fila de {@code turno} la representa, y 08.02 no va a crear un turno por inscripto:
 * cada participante va a tener su {@code InscripcionClase}, que cuelga de esta fila. Lo unico que
 * clase y turno comparten es el lock de {@code agenda_sede}.
 *
 * <h2>Ocupa recursos exactamente igual que un turno</h2>
 *
 * <p>Un profesional y un espacio, durante un intervalo. Por eso la exclusion tiene que ser la
 * misma: si una clase se serializara contra un punto propio, una reserva de turno y una clase no
 * se verian y las dos ganarian el mismo box, <b>sin que nada falle</b>. Ver la cabecera de
 * {@code V58} y {@code ClaseService}.
 *
 * <h2>Lo que NO tiene, y es deliberado</h2>
 *
 * <p><b>Ni {@code active} ni {@code cupoOcupado}.</b> {@code active} seria una segunda fuente de
 * verdad sobre lo mismo que dice {@link #estado}; la ocupacion se cuenta al leer desde las
 * inscripciones de 08.02, porque materializarla es una segunda copia de la verdad —la misma
 * decision que el timeline clinico de 04.02 y la disponibilidad efectiva de 02.04—.
 */
@Entity
@Table(name = "clase_programada")
public class ClaseProgramada {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	@Column(name = "profesional_membership_id")
	private Long profesionalMembershipId;

	@Column(name = "espacio_id")
	private Long espacioId;

	@Column(name = "titulo", length = 120)
	private String titulo;

	@Column(name = "inicio", nullable = false)
	private Instant inicio;

	@Column(name = "fin", nullable = false)
	private Instant fin;

	@Column(name = "capacidad", nullable = false)
	private int capacidad;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoClase estado;

	@Column(name = "idempotency_key", length = 80, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	@Column(name = "programado_por_cuenta_id", nullable = false, updatable = false)
	private Long programadoPorCuentaId;

	@Column(name = "programado_en", nullable = false, updatable = false)
	private Instant programadoEn;

	@Column(name = "reprogramado_en")
	private Instant reprogramadoEn;

	@Column(name = "motivo_cancelacion", length = 300)
	private String motivoCancelacion;

	@Column(name = "cancelado_en")
	private Instant canceladoEn;

	@Column(name = "cancelado_por_cuenta_id")
	private Long canceladoPorCuentaId;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected ClaseProgramada() {
		// Requerido por JPA.
	}

	public ClaseProgramada(
			long organizationId,
			long consultorioId,
			long ofertaId,
			Long profesionalMembershipId,
			Long espacioId,
			String titulo,
			Instant inicio,
			Instant fin,
			int capacidad,
			long programadoPorCuentaId,
			Instant programadoEn,
			String idempotencyKey,
			String requestHash) {

		if (!fin.isAfter(inicio)) {
			throw new IllegalArgumentException(
					"Una clase termina despues de empezar: " + inicio + " -> " + fin);
		}
		// Los CHECK de V58 dicen lo mismo del lado de la base. Se valida en las dos puntas a
		// proposito: la base impide la fila corrupta aunque alguien inserte por fuera de JPA, y
		// esta validacion da un mensaje que nombra el problema en vez de un error de constraint.
		if (capacidad <= 1) {
			throw new IllegalArgumentException(
					"Una clase de capacidad " + capacidad + " es un turno individual mal rotulado");
		}
		if ((idempotencyKey == null) != (requestHash == null)) {
			throw new IllegalArgumentException(
					"La clave de idempotencia y el hash del pedido viajan juntos o no viajan");
		}

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.ofertaId = ofertaId;
		this.profesionalMembershipId = profesionalMembershipId;
		this.espacioId = espacioId;
		this.titulo = titulo == null || titulo.isBlank() ? null : titulo.strip();
		this.inicio = inicio;
		this.fin = fin;
		this.capacidad = capacidad;
		this.estado = EstadoClase.PROGRAMADA;
		this.programadoPorCuentaId = programadoPorCuentaId;
		this.programadoEn = programadoEn;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
	}

	/**
	 * Mueve la clase a otro intervalo, profesional, espacio o capacidad. <b>Es la misma clase.</b>
	 *
	 * <p>RF-M28-005 y CA-M28-005-06: conserva id, historial y —cuando 08.02 las cree— sus
	 * inscripciones, que cuelgan de este id. Un reemplazo por par cancelada/nueva cortaria esa
	 * cadena y perderia a todos los participantes, que es exactamente lo que el criterio de
	 * aceptacion prohibe.
	 *
	 * <p><b>No vuelve a ningun estado anterior</b>, a diferencia de {@code Turno#reprogramar}, que
	 * se des-confirma: una clase no tiene confirmacion propia. Lo que si cambia de estado son las
	 * inscripciones afectadas, y eso es de 08.02.
	 *
	 * <p>La trazabilidad la da {@link ClaseEvento}, que guarda el intervalo y la capacidad
	 * anteriores.
	 *
	 * @throws TransicionDeClaseNoPermitidaException si la clase ya cerro su ciclo o ya empezo
	 */
	public void reprogramar(
			Instant nuevoInicio,
			Instant nuevoFin,
			Long nuevoProfesionalId,
			Long nuevoEspacioId,
			int nuevaCapacidad,
			Instant occurredAt) {

		exigirTransitable();
		if (!inicio.isAfter(occurredAt)) {
			throw new TransicionDeClaseNoPermitidaException(id, "ya empezo y no se puede mover");
		}
		if (!nuevoInicio.isAfter(occurredAt)) {
			throw new TransicionDeClaseNoPermitidaException(
					id, "el horario nuevo esta en el pasado");
		}
		if (!nuevoFin.isAfter(nuevoInicio)) {
			throw new IllegalArgumentException(
					"Una clase termina despues de empezar: " + nuevoInicio + " -> " + nuevoFin);
		}
		if (nuevaCapacidad <= 1) {
			throw new IllegalArgumentException(
					"Una clase de capacidad " + nuevaCapacidad + " es un turno individual mal rotulado");
		}
		this.inicio = nuevoInicio;
		this.fin = nuevoFin;
		this.profesionalMembershipId = nuevoProfesionalId;
		this.espacioId = nuevoEspacioId;
		this.capacidad = nuevaCapacidad;
		this.reprogramadoEn = occurredAt;
	}

	/**
	 * Cancela la clase con motivo declarado. <b>Libera el recurso y conserva la fila.</b>
	 *
	 * <p>RN-M28-009 y la regla maestra 10: cancelar no elimina fisicamente, exige motivo y queda
	 * auditado. La baja logica es lo que libera el horario —las consultas de solapamiento filtran
	 * por {@code deletedAt IS NULL}— asi que el box vuelve a estar disponible para un turno sin
	 * borrar nada.
	 *
	 * <p><b>Es idempotente</b>, a diferencia de {@code Turno#cancelar}. CA-M28-006-06 lo exige
	 * literalmente —"una segunda ejecucion no devuelve creditos ni dinero dos veces"— y cuando 08.02
	 * y 08.07 cuelguen reversas de credito de esta operacion, la idempotencia deja de ser una
	 * comodidad de pantalla y pasa a ser lo que impide devolver plata dos veces. Se decide aca y no
	 * alli: la regla tiene que existir antes que el dinero que protege.
	 *
	 * @return {@code true} si esta llamada fue la que cancelo; {@code false} si ya estaba cancelada
	 */
	public boolean cancelar(String motivo, long cuentaId, Instant occurredAt) {
		if (estado == EstadoClase.CANCELADA) {
			return false;
		}
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("El motivo de cancelacion es obligatorio (RN-M28-009)");
		}
		this.estado = EstadoClase.CANCELADA;
		this.motivoCancelacion = motivo.strip();
		this.canceladoEn = occurredAt;
		this.canceladoPorCuentaId = cuentaId;
		this.deletedAt = occurredAt;
		return true;
	}

	private void exigirTransitable() {
		if (!estado.admiteTransicion()) {
			throw new TransicionDeClaseNoPermitidaException(
					id, "ya esta " + estado.name().toLowerCase());
		}
	}

	/** Una clase viva ocupa recursos. Una cancelada no: su baja logica es lo que los libera. */
	public boolean estaViva() {
		return deletedAt == null;
	}

	/** {@code true} si esta clase se cruza con {@code [desde, hasta)}. Extremos superiores exclusivos. */
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

	public Long getProfesionalMembershipId() {
		return profesionalMembershipId;
	}

	public Long getEspacioId() {
		return espacioId;
	}

	public String getTitulo() {
		return titulo;
	}

	public Instant getInicio() {
		return inicio;
	}

	public Instant getFin() {
		return fin;
	}

	public int getCapacidad() {
		return capacidad;
	}

	public EstadoClase getEstado() {
		return estado;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public Long getProgramadoPorCuentaId() {
		return programadoPorCuentaId;
	}

	public Instant getProgramadoEn() {
		return programadoEn;
	}

	public Instant getReprogramadoEn() {
		return reprogramadoEn;
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

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public long getVersion() {
		return version;
	}
}
