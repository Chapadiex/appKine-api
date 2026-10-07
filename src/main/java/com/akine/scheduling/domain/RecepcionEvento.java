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
 * Una transicion de la {@link Recepcion}: actor, hora, estado anterior, estado nuevo y motivo
 * (DP-05, DP-16).
 *
 * <p><b>Append-only</b>: todas las columnas son {@code updatable = false} y el puerto no expone ni
 * modificacion ni borrado. La recepcion guarda el estado actual; esto es lo unico que dice como se
 * llego ahi —y lo unico que queda de un check-in anulado—.
 */
@Entity
@Table(name = "recepcion_evento")
public class RecepcionEvento {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "recepcion_id", nullable = false, updatable = false)
	private Long recepcionId;

	@Column(name = "turno_id", nullable = false, updatable = false)
	private Long turnoId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 24, updatable = false)
	private TipoEventoRecepcion tipo;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado_anterior", length = 16, updatable = false)
	private EstadoRecepcion estadoAnterior;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado_nuevo", nullable = false, length = 16, updatable = false)
	private EstadoRecepcion estadoNuevo;

	@Column(name = "motivo", length = 1000, updatable = false)
	private String motivo;

	@Column(name = "actor_cuenta_id", updatable = false)
	private Long actorCuentaId;

	@Column(name = "ocurrido_en", nullable = false, updatable = false)
	private Instant ocurridoEn;

	protected RecepcionEvento() {
		// Requerido por JPA.
	}

	/** El evento de una transicion ya aplicada: el estado nuevo se lee de la recepcion. */
	public static RecepcionEvento de(
			Recepcion recepcion, TipoEventoRecepcion tipo, EstadoRecepcion estadoAnterior,
			String motivo, Long actorCuentaId, Instant ocurridoEn) {

		RecepcionEvento evento = new RecepcionEvento();
		evento.organizationId = recepcion.getOrganizationId();
		evento.consultorioId = recepcion.getConsultorioId();
		evento.recepcionId = recepcion.getId();
		evento.turnoId = recepcion.getTurnoId();
		evento.tipo = tipo;
		evento.estadoAnterior = estadoAnterior;
		evento.estadoNuevo = recepcion.getEstado();
		evento.motivo = motivo == null || motivo.isBlank()
				? null
				: (motivo.length() <= 1000 ? motivo : motivo.substring(0, 1000));
		evento.actorCuentaId = actorCuentaId;
		evento.ocurridoEn = ocurridoEn;
		return evento;
	}

	public Long getId() {
		return id;
	}

	public Long getRecepcionId() {
		return recepcionId;
	}

	public Long getTurnoId() {
		return turnoId;
	}

	public TipoEventoRecepcion getTipo() {
		return tipo;
	}

	public EstadoRecepcion getEstadoAnterior() {
		return estadoAnterior;
	}

	public EstadoRecepcion getEstadoNuevo() {
		return estadoNuevo;
	}

	public String getMotivo() {
		return motivo;
	}

	public Long getActorCuentaId() {
		return actorCuentaId;
	}

	public Instant getOcurridoEn() {
		return ocurridoEn;
	}
}
