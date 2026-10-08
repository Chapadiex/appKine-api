package com.akine.person.domain;

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
 * Un hecho del historial de una {@link Autorizacion}: que paso, de que estado a cual, quien y
 * cuando (DP-23, M17).
 *
 * <p><b>Append-only</b>: todas las columnas son {@code updatable = false} y el puerto no expone ni
 * modificacion ni borrado. Mismo patron que {@code turno_evento} y {@code recepcion_evento}. La
 * autorizacion guarda el estado actual; esto es lo unico que dice como se llego ahi. No se arma
 * leyendo {@code audit_event}: ese es otro dato, con otro permiso y otro propietario.
 *
 * <p>El estado nuevo y el ciclo de vida se leen de la autorizacion <b>despues</b> de aplicar la
 * mutacion, asi que el evento se construye cuando la entidad ya cambio.
 */
@Entity
@Table(name = "autorizacion_evento")
public class AutorizacionEvento {

	/** Tope de {@code detalle} en la base. */
	public static final int DETALLE_MAXIMO = 500;

	/** Tope de {@code motivo} en la base. */
	public static final int MOTIVO_MAXIMO = 1000;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "autorizacion_id", nullable = false, updatable = false)
	private Long autorizacionId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "consultorio_id", updatable = false)
	private Long consultorioId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 24, updatable = false)
	private TipoEventoAutorizacion tipo;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado_anterior", length = 16, updatable = false)
	private EstadoAutorizacion estadoAnterior;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado_nuevo", nullable = false, length = 16, updatable = false)
	private EstadoAutorizacion estadoNuevo;

	@Column(name = "activa", nullable = false, updatable = false)
	private boolean activa;

	@Column(name = "cantidad", updatable = false)
	private Integer cantidad;

	@Column(name = "movimiento_id", updatable = false)
	private Long movimientoId;

	@Column(name = "detalle", length = DETALLE_MAXIMO, updatable = false)
	private String detalle;

	@Column(name = "motivo", length = MOTIVO_MAXIMO, updatable = false)
	private String motivo;

	@Column(name = "actor_cuenta_id", updatable = false)
	private Long actorCuentaId;

	@Column(name = "ocurrido_en", nullable = false, updatable = false)
	private Instant ocurridoEn;

	protected AutorizacionEvento() {
		// Requerido por JPA.
	}

	/**
	 * Un hecho que no toca el ledger: alta, resolucion, edicion, documento o anulacion.
	 *
	 * @param estadoAnterior {@code null} solo en el {@link TipoEventoAutorizacion#ALTA ALTA}
	 */
	@SuppressWarnings("java:S107")
	public static AutorizacionEvento de(
			Autorizacion autorizacion,
			TipoEventoAutorizacion tipo,
			EstadoAutorizacion estadoAnterior,
			String detalle,
			String motivo,
			Long consultorioId,
			Long actorCuentaId,
			Instant ocurridoEn) {

		if (tipo == TipoEventoAutorizacion.CONSUMO
				|| tipo == TipoEventoAutorizacion.REVERSION_DE_CONSUMO) {
			throw new IllegalArgumentException(
					"Un hecho del ledger se registra con su movimiento: ver delLedger");
		}
		if ((tipo == TipoEventoAutorizacion.ALTA) != (estadoAnterior == null)) {
			throw new IllegalArgumentException(
					"Solo el alta no tiene estado anterior: tipo=" + tipo);
		}
		return nuevo(autorizacion, tipo, estadoAnterior, null, null, detalle, motivo,
				consultorioId, actorCuentaId, ocurridoEn);
	}

	/**
	 * Un consumo o una reversion: lleva el movimiento del ledger que lo produjo y la cantidad.
	 * El estado no cambia —consumir no resuelve nada—, asi que anterior y nuevo son el mismo.
	 */
	@SuppressWarnings("java:S107")
	public static AutorizacionEvento delLedger(
			Autorizacion autorizacion,
			TipoEventoAutorizacion tipo,
			long movimientoId,
			int cantidad,
			String detalle,
			String motivo,
			Long consultorioId,
			Long actorCuentaId,
			Instant ocurridoEn) {

		if (tipo != TipoEventoAutorizacion.CONSUMO
				&& tipo != TipoEventoAutorizacion.REVERSION_DE_CONSUMO) {
			throw new IllegalArgumentException("Solo consumo y reversion vienen del ledger: " + tipo);
		}
		if (cantidad <= 0) {
			throw new IllegalArgumentException("La cantidad del ledger es positiva: " + cantidad);
		}
		return nuevo(autorizacion, tipo, autorizacion.getEstado(), movimientoId, cantidad,
				detalle, motivo, consultorioId, actorCuentaId, ocurridoEn);
	}

	@SuppressWarnings("java:S107")
	private static AutorizacionEvento nuevo(
			Autorizacion autorizacion,
			TipoEventoAutorizacion tipo,
			EstadoAutorizacion estadoAnterior,
			Long movimientoId,
			Integer cantidad,
			String detalle,
			String motivo,
			Long consultorioId,
			Long actorCuentaId,
			Instant ocurridoEn) {

		AutorizacionEvento evento = new AutorizacionEvento();
		evento.organizationId = autorizacion.getOrganizationId();
		evento.autorizacionId = autorizacion.getId();
		evento.personaId = autorizacion.getPersonaId();
		evento.consultorioId = consultorioId == null ? autorizacion.getConsultorioId() : consultorioId;
		evento.tipo = tipo;
		evento.estadoAnterior = estadoAnterior;
		evento.estadoNuevo = autorizacion.getEstado();
		evento.activa = autorizacion.isActive();
		evento.movimientoId = movimientoId;
		evento.cantidad = cantidad;
		evento.detalle = recortar(detalle, DETALLE_MAXIMO);
		evento.motivo = recortar(motivo, MOTIVO_MAXIMO);
		evento.actorCuentaId = actorCuentaId;
		evento.ocurridoEn = ocurridoEn == null ? Instant.now() : ocurridoEn;
		return evento;
	}

	private static String recortar(String texto, int maximo) {
		if (texto == null || texto.isBlank()) {
			return null;
		}
		String limpio = texto.strip();
		return limpio.length() <= maximo ? limpio : limpio.substring(0, maximo);
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getAutorizacionId() {
		return autorizacionId;
	}

	public Long getPersonaId() {
		return personaId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public TipoEventoAutorizacion getTipo() {
		return tipo;
	}

	public EstadoAutorizacion getEstadoAnterior() {
		return estadoAnterior;
	}

	public EstadoAutorizacion getEstadoNuevo() {
		return estadoNuevo;
	}

	public boolean isActiva() {
		return activa;
	}

	public Integer getCantidad() {
		return cantidad;
	}

	public Long getMovimientoId() {
		return movimientoId;
	}

	public String getDetalle() {
		return detalle;
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
