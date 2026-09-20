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
 * Una transicion del Plan de Tratamiento, asentada para siempre (RF-M11-007).
 *
 * <h2>Append-only, y la ausencia es el diseño</h2>
 *
 * <p>No tiene {@code version}, ni {@code updated_at}, ni baja logica, y su puerto no declara
 * {@code update} ni {@code delete}: <b>un historial que se puede editar no es un historial</b>.
 * Mismo diseño y mismo argumento que {@link CasoEvento} y que {@code turno_evento} en 05.03.
 *
 * <p>Se escribe <b>dentro</b> de la transaccion que produce el cambio, no despues: un evento
 * escrito post-commit puede perderse y dejar la transicion sin rastro, que es exactamente el
 * agujero que este historial existe para tapar.
 *
 * <h2>Esto no es la auditoria, y no la reemplaza</h2>
 *
 * <p>{@code audit_event} (V5, V14) registra <b>accesos</b> y lo lee quien audita, con
 * {@code auditoria:read}. Esto registra <b>estados del plan</b> y lo lee el profesional que abre la
 * ficha, con {@code hc:read}. Son dos lectores, dos permisos y dos preguntas distintas.
 *
 * <p>Por eso {@link #detalle} <b>nunca lleva contenido clinico</b>: dice que se modificaron los
 * objetivos, no cuales. Lo que si lleva es {@link #numeroVersion}, que ata la modificacion a su
 * contenido sin copiarlo — quien quiera leerlo pasa por la consulta de versiones, con su propio
 * acceso auditado.
 */
@Entity
@Table(name = "plan_evento")
public class PlanEvento {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "plan_tratamiento_id", nullable = false, updatable = false)
	private Long planTratamientoId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, updatable = false, length = 24)
	private TipoEventoPlan tipo;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado_anterior", updatable = false, length = 16)
	private EstadoPlan estadoAnterior;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado_nuevo", nullable = false, updatable = false, length = 16)
	private EstadoPlan estadoNuevo;

	@Column(name = "motivo", updatable = false, length = 500)
	private String motivo;

	@Column(name = "detalle", updatable = false, length = 500)
	private String detalle;

	@Column(name = "numero_version", updatable = false)
	private Integer numeroVersion;

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

	protected PlanEvento() {
		// Requerido por JPA.
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public PlanEvento(
			Long organizationId,
			Long planTratamientoId,
			TipoEventoPlan tipo,
			EstadoPlan estadoAnterior,
			EstadoPlan estadoNuevo,
			String motivo,
			String detalle,
			Integer numeroVersion,
			Instant ocurrioEn,
			Long actorCuentaId) {

		this.organizationId = organizationId;
		this.planTratamientoId = planTratamientoId;
		this.tipo = tipo;
		this.estadoAnterior = estadoAnterior;
		this.estadoNuevo = estadoNuevo;
		this.motivo = motivo;
		this.detalle = detalle;
		this.numeroVersion = numeroVersion;
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

	public Long getPlanTratamientoId() {
		return planTratamientoId;
	}

	public TipoEventoPlan getTipo() {
		return tipo;
	}

	public EstadoPlan getEstadoAnterior() {
		return estadoAnterior;
	}

	public EstadoPlan getEstadoNuevo() {
		return estadoNuevo;
	}

	public String getMotivo() {
		return motivo;
	}

	public String getDetalle() {
		return detalle;
	}

	public Integer getNumeroVersion() {
		return numeroVersion;
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
