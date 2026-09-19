package com.akine.clinical.domain;

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
 * Una transicion del Caso Clinico, asentada para siempre (RF-M10-006).
 *
 * <h2>Append-only, y la ausencia es el diseño</h2>
 *
 * <p>No tiene {@code version}, ni {@code updated_at}, ni baja logica, y su puerto no declara
 * {@code update} ni {@code delete}: <b>un historial que se puede editar no es un historial</b>.
 * Mismo diseño y mismo argumento que {@code turno_evento} en 05.03.
 *
 * <p>Se escribe <b>dentro</b> de la transaccion que produce el cambio, no despues: un evento
 * escrito post-commit puede perderse y dejar la transicion sin rastro, que es exactamente el
 * agujero que este historial existe para tapar.
 *
 * <h2>Esto no es la auditoria, y no la reemplaza</h2>
 *
 * <p>{@code audit_event} (V5, V14) registra <b>accesos</b> y lo lee quien audita, con
 * {@code auditoria:read}. Esto registra <b>estados del caso</b> y lo lee el profesional que abre
 * la ficha, con {@code hc:read}. Son dos lectores, dos permisos y dos preguntas distintas.
 *
 * <p>Por eso {@link #detalle} <b>nunca lleva contenido clinico</b>: dice que se edito el objetivo,
 * no cual era. El diagnostico y el objetivo se leen del caso, con su propio acceso auditado.
 */
@Entity
@Table(name = "caso_evento")
public class CasoEvento {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "caso_id", nullable = false, updatable = false)
	private Long casoId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, updatable = false, length = 24)
	private TipoEventoCaso tipo;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado_anterior", updatable = false, length = 16)
	private EstadoCaso estadoAnterior;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado_nuevo", nullable = false, updatable = false, length = 16)
	private EstadoCaso estadoNuevo;

	@Column(name = "motivo", updatable = false, length = 500)
	private String motivo;

	@Column(name = "detalle", updatable = false, length = 500)
	private String detalle;

	@Column(name = "ocurrio_en", nullable = false, updatable = false)
	private Instant ocurrioEn;

	@Column(name = "actor_cuenta_id", nullable = false, updatable = false)
	private Long actorCuentaId;

	/**
	 * Marca de insercion propia.
	 *
	 * <p>Esta clase <b>no</b> extiende {@code MarcaTemporal} a proposito: esa trae
	 * {@code updated_at}, y una fila de historial que declara haber sido modificada es una
	 * contradiccion. La tabla tampoco tiene esa columna.
	 */
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected CasoEvento() {
		// Requerido por JPA.
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public CasoEvento(
			Long organizationId,
			Long casoId,
			TipoEventoCaso tipo,
			EstadoCaso estadoAnterior,
			EstadoCaso estadoNuevo,
			String motivo,
			String detalle,
			Instant ocurrioEn,
			Long actorCuentaId) {

		this.organizationId = organizationId;
		this.casoId = casoId;
		this.tipo = tipo;
		this.estadoAnterior = estadoAnterior;
		this.estadoNuevo = estadoNuevo;
		this.motivo = motivo;
		this.detalle = detalle;
		this.ocurrioEn = ocurrioEn;
		this.actorCuentaId = actorCuentaId;
		this.createdAt = ocurrioEn;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getCasoId() {
		return casoId;
	}

	public TipoEventoCaso getTipo() {
		return tipo;
	}

	public EstadoCaso getEstadoAnterior() {
		return estadoAnterior;
	}

	public EstadoCaso getEstadoNuevo() {
		return estadoNuevo;
	}

	public String getMotivo() {
		return motivo;
	}

	public String getDetalle() {
		return detalle;
	}

	public Instant getOcurrioEn() {
		return ocurrioEn;
	}

	public Long getActorCuentaId() {
		return actorCuentaId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
