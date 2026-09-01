package com.akine.scheduling.domain;

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
 * Una transicion de un Turno, tal como ocurrio. <b>Append-only</b> (RF-M12-008).
 *
 * <p>No tiene setters, ni {@code @Version}, ni baja logica: un historial que se puede editar no es
 * un historial. Todos los campos se fijan en el constructor y la unica operacion que existe sobre
 * esta tabla es el INSERT.
 *
 * <p><b>Se escribe dentro de la transaccion del cambio de estado</b>, igual que la auditoria de
 * plataforma y por el mismo motivo: un evento emitido despues del commit puede perderse y dejar la
 * transicion sin rastro. Si el historial falla, la transicion no se confirma.
 *
 * <p>No duplica a {@code audit_event}: aquella tabla es transversal, la consulta un administrador
 * y su clave es el actor. Esta es del dominio de M12, la consulta la pantalla del turno y su clave
 * es el turno. Las dos se escriben, y a proposito.
 */
@Entity
@Table(name = "turno_evento")
public class TurnoEvento {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "turno_id", nullable = false, updatable = false)
	private Long turnoId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 20, updatable = false)
	private TipoEventoTurno tipo;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado_anterior", length = 16, updatable = false)
	private EstadoTurno estadoAnterior;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado_nuevo", nullable = false, length = 16, updatable = false)
	private EstadoTurno estadoNuevo;

	@Column(name = "motivo", length = 300, updatable = false)
	private String motivo;

	@Column(name = "inicio_anterior", updatable = false)
	private Instant inicioAnterior;

	@Column(name = "fin_anterior", updatable = false)
	private Instant finAnterior;

	@Column(name = "inicio_nuevo", updatable = false)
	private Instant inicioNuevo;

	@Column(name = "fin_nuevo", updatable = false)
	private Instant finNuevo;

	@Column(name = "actor_cuenta_id", updatable = false)
	private Long actorCuentaId;

	@Column(name = "ocurrido_en", nullable = false, updatable = false)
	private Instant ocurridoEn;

	protected TurnoEvento() {
		// Requerido por JPA.
	}

	private TurnoEvento(
			long organizationId,
			long consultorioId,
			long turnoId,
			TipoEventoTurno tipo,
			EstadoTurno estadoAnterior,
			EstadoTurno estadoNuevo,
			String motivo,
			Long actorCuentaId,
			Instant ocurridoEn) {

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.turnoId = turnoId;
		this.tipo = tipo;
		this.estadoAnterior = estadoAnterior;
		this.estadoNuevo = estadoNuevo;
		this.motivo = motivo;
		this.actorCuentaId = actorCuentaId;
		this.ocurridoEn = ocurridoEn;
	}

	/**
	 * Evento de una transicion que no mueve el turno de horario.
	 *
	 * @param estadoAnterior {@code null} solo en {@link TipoEventoTurno#RESERVA}
	 */
	public static TurnoEvento de(
			Turno turno,
			TipoEventoTurno tipo,
			EstadoTurno estadoAnterior,
			String motivo,
			Long actorCuentaId,
			Instant ocurridoEn) {

		return new TurnoEvento(
				turno.getOrganizationId(), turno.getConsultorioId(), turno.getId(),
				tipo, estadoAnterior, turno.getEstado(), motivo, actorCuentaId, ocurridoEn);
	}

	/**
	 * Evento de reprogramacion: el unico que lleva los dos intervalos.
	 *
	 * <p>El turno ya viene movido, asi que el intervalo nuevo se lee de el y el anterior tiene que
	 * llegar por parametro: la fila ya no lo tiene, y es exactamente lo que RN-M12-003 pide
	 * conservar.
	 */
	public static TurnoEvento reprogramacion(
			Turno turno,
			EstadoTurno estadoAnterior,
			Instant inicioAnterior,
			Instant finAnterior,
			String motivo,
			Long actorCuentaId,
			Instant ocurridoEn) {

		TurnoEvento evento = new TurnoEvento(
				turno.getOrganizationId(), turno.getConsultorioId(), turno.getId(),
				TipoEventoTurno.REPROGRAMACION, estadoAnterior, turno.getEstado(),
				motivo, actorCuentaId, ocurridoEn);
		evento.inicioAnterior = inicioAnterior;
		evento.finAnterior = finAnterior;
		evento.inicioNuevo = turno.getInicio();
		evento.finNuevo = turno.getFin();
		return evento;
	}

	public Long getId() {
		return id;
	}

	public Long getTurnoId() {
		return turnoId;
	}

	public TipoEventoTurno getTipo() {
		return tipo;
	}

	public EstadoTurno getEstadoAnterior() {
		return estadoAnterior;
	}

	public EstadoTurno getEstadoNuevo() {
		return estadoNuevo;
	}

	public String getMotivo() {
		return motivo;
	}

	public Instant getInicioAnterior() {
		return inicioAnterior;
	}

	public Instant getFinAnterior() {
		return finAnterior;
	}

	public Instant getInicioNuevo() {
		return inicioNuevo;
	}

	public Instant getFinNuevo() {
		return finNuevo;
	}

	public Long getActorCuentaId() {
		return actorCuentaId;
	}

	public Instant getOcurridoEn() {
		return ocurridoEn;
	}
}
