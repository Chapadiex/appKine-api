package com.akine.activity.domain;

import com.akine.activity.domain.exception.TransicionDeInscripcionNoPermitidaException;
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
 * Un participante en una clase (M28, RN-M28-003).
 *
 * <h2>Lo que esta fila NO es</h2>
 *
 * <p><b>No es un turno.</b> Ninguna fila de {@code turno} la acompana (CA-M28-001-06): la clase
 * ocupo el box y el profesional una sola vez, en 08.01, y el cupo no vuelve a tocar la agenda.
 *
 * <p><b>No convierte a nadie en paciente.</b> Apunta a una {@code persona} y no exige perfil de
 * paciente vigente: anotarse en una clase de yoga no es entrar al circuito clinico. Crear un
 * {@code perfil_paciente} lo hace {@code PerfilPacienteService} y solo el (RF-M07-010); la
 * derivacion es 08.04.
 *
 * <p><b>No devenga nada.</b> Reservar un lugar no prueba que nadie haya entrenado (RN-M28-007,
 * DP-05). El devengo llega con la asistencia (08.03) y la integracion economica (08.09).
 *
 * <h2>Y lo que no decide</h2>
 *
 * <p><b>No decide si hay lugar.</b> Esta fila es el <em>recibo</em> de un lugar que ya se otorgo:
 * quien lo otorga es el {@code UPDATE} condicional sobre {@code clase_programada.cupo_ocupado}, que
 * es lo unico que puede hacerlo sin dejar una ventana entre leer y escribir. Ver la cabecera de
 * {@code V60}. Un constructor que "verificara el cupo" seria exactamente el {@code if} del servicio
 * que el diseno evita.
 */
@Entity
@Table(name = "inscripcion_clase")
public class InscripcionClase {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "clase_id", nullable = false, updatable = false)
	private Long claseId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 16)
	private EstadoInscripcion estado;

	@Column(name = "posicion_espera")
	private Integer posicionEspera;

	@Column(name = "idempotency_key", length = 80, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	@Column(name = "inscripto_por_cuenta_id", nullable = false, updatable = false)
	private Long inscriptoPorCuentaId;

	@Column(name = "inscripto_en", nullable = false, updatable = false)
	private Instant inscriptoEn;

	@Column(name = "confirmada_en")
	private Instant confirmadaEn;

	@Column(name = "promovida_en")
	private Instant promovidaEn;

	@Column(name = "motivo_cancelacion", length = 300)
	private String motivoCancelacion;

	@Column(name = "cancelada_en")
	private Instant canceladaEn;

	@Column(name = "cancelada_por_cuenta_id")
	private Long canceladaPorCuentaId;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected InscripcionClase() {
		// Requerido por JPA.
	}

	private InscripcionClase(
			long organizationId,
			long consultorioId,
			long claseId,
			long personaId,
			EstadoInscripcion estado,
			Integer posicionEspera,
			long inscriptoPorCuentaId,
			Instant inscriptoEn,
			String idempotencyKey,
			String requestHash) {

		if ((idempotencyKey == null) != (requestHash == null)) {
			throw new IllegalArgumentException(
					"La clave de idempotencia y el hash del pedido viajan juntos o no viajan");
		}
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.claseId = claseId;
		this.personaId = personaId;
		this.estado = estado;
		this.posicionEspera = posicionEspera;
		this.inscriptoPorCuentaId = inscriptoPorCuentaId;
		this.inscriptoEn = inscriptoEn;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
	}

	/**
	 * Alta de quien <b>ya tiene el lugar</b>: el {@code UPDATE} condicional del cupo afecto una
	 * fila antes de llegar aca.
	 */
	public static InscripcionClase conLugar(
			long organizationId,
			long consultorioId,
			long claseId,
			long personaId,
			long inscriptoPorCuentaId,
			Instant inscriptoEn,
			String idempotencyKey,
			String requestHash) {

		return new InscripcionClase(organizationId, consultorioId, claseId, personaId,
				EstadoInscripcion.RESERVADA, null, inscriptoPorCuentaId, inscriptoEn,
				idempotencyKey, requestHash);
	}

	/**
	 * Alta en la cola, con posicion ya asignada por el contador de la clase.
	 *
	 * <p>La posicion llega asignada y no se calcula aca a proposito: sale de
	 * {@code UPDATE ... ultima_posicion_espera + 1}, nunca de un {@code MAX + 1}, que repite numeros
	 * en cuanto hay dos altas a la vez.
	 */
	public static InscripcionClase enEspera(
			long organizationId,
			long consultorioId,
			long claseId,
			long personaId,
			int posicion,
			long inscriptoPorCuentaId,
			Instant inscriptoEn,
			String idempotencyKey,
			String requestHash) {

		if (posicion <= 0) {
			throw new IllegalArgumentException("Una posicion de espera empieza en 1: " + posicion);
		}
		return new InscripcionClase(organizationId, consultorioId, claseId, personaId,
				EstadoInscripcion.LISTA_ESPERA, posicion, inscriptoPorCuentaId, inscriptoEn,
				idempotencyKey, requestHash);
	}

	/**
	 * {@code RESERVADA} -> {@code CONFIRMADA}. <b>No toca el cupo</b>: los dos estados lo consumen,
	 * asi que la transicion no otorga ni libera nada.
	 *
	 * @return {@code true} si esta llamada fue la que confirmo; {@code false} si ya estaba
	 */
	public boolean confirmar(Instant occurredAt) {
		if (estado == EstadoInscripcion.CONFIRMADA) {
			return false;
		}
		if (estado != EstadoInscripcion.RESERVADA) {
			throw new TransicionDeInscripcionNoPermitidaException(
					id, "esta " + estado.name().toLowerCase() + " y solo se confirma una reservada");
		}
		this.estado = EstadoInscripcion.CONFIRMADA;
		this.confirmadaEn = occurredAt;
		return true;
	}

	/**
	 * Cancela y <b>conserva la fila</b> (RF-M28-003: "liberar cupo y conservar historial
	 * individual"; su validacion obligatoria dice "no borrar fisicamente").
	 *
	 * <p><b>Es idempotente.</b> Cancelar una cancelada devuelve {@code false} y no cambia nada. No
	 * es comodidad de pantalla: cuando 08.07 cuelgue de esta operacion la devolucion de creditos,
	 * una segunda ejecucion que liberara el lugar de nuevo dejaria el contador por debajo de los
	 * recibos, y una que devolviera credito lo devolveria dos veces.
	 *
	 * <p>{@code posicionEspera} <b>se conserva</b>: es la prueba de en que orden habia llegado.
	 *
	 * @return {@code true} si esta llamada fue la que cancelo
	 * @throws TransicionDeInscripcionNoPermitidaException si ya se registro asistencia
	 */
	public boolean cancelar(String motivo, long cuentaId, Instant occurredAt) {
		if (estado == EstadoInscripcion.CANCELADA) {
			return false;
		}
		if (estado == EstadoInscripcion.ASISTIO || estado == EstadoInscripcion.AUSENTE) {
			throw new TransicionDeInscripcionNoPermitidaException(
					id, "ya tiene asistencia registrada y no se cancela: se enmienda (08.03)");
		}
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException(
					"El motivo de cancelacion es obligatorio (RN-M28-009)");
		}
		this.estado = EstadoInscripcion.CANCELADA;
		this.motivoCancelacion = motivo.strip();
		this.canceladaEn = occurredAt;
		this.canceladaPorCuentaId = cuentaId;
		this.deletedAt = occurredAt;
		return true;
	}

	/** {@code true} si esta fila ocupa uno de los lugares de la clase. */
	public boolean consumeCupo() {
		return estado.consumeCupo();
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

	public Long getClaseId() {
		return claseId;
	}

	public Long getPersonaId() {
		return personaId;
	}

	public EstadoInscripcion getEstado() {
		return estado;
	}

	public Integer getPosicionEspera() {
		return posicionEspera;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public Long getInscriptoPorCuentaId() {
		return inscriptoPorCuentaId;
	}

	public Instant getInscriptoEn() {
		return inscriptoEn;
	}

	public Instant getConfirmadaEn() {
		return confirmadaEn;
	}

	public Instant getPromovidaEn() {
		return promovidaEn;
	}

	public String getMotivoCancelacion() {
		return motivoCancelacion;
	}

	public Instant getCanceladaEn() {
		return canceladaEn;
	}

	public Long getCanceladaPorCuentaId() {
		return canceladaPorCuentaId;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public long getVersion() {
		return version;
	}
}
