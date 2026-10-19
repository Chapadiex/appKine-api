package com.akine.encounter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Un reemplazo del profesional de una sesion abierta: quien a quien, quien lo decidio y por que
 * (U-24, DP-43).
 *
 * <p>Es el "registro propio de quien reemplaza a quien y por que" que {@code Sesion#exigirPropiedadDe}
 * declaraba inexistente desde 06.01. <b>Append-only</b>: sin setters, sin baja logica, sin
 * {@code @Version}. Un hecho pasado no se corrige; si el reemplazo fue un error, se reemplaza de
 * vuelta y quedan las dos filas.
 *
 * <p>El motivo vive aca y no en {@code audit_event}, igual que el de la enmienda: la auditoria se
 * lee con {@code auditoria:read}, que no es un permiso clinico.
 */
@Entity
@Table(name = "sesion_reemplazo_profesional")
public class SesionReemplazoProfesional {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "sesion_id", nullable = false, updatable = false)
	private Long sesionId;

	@Column(name = "profesional_anterior_membership_id", nullable = false, updatable = false)
	private Long profesionalAnteriorMembershipId;

	@Column(name = "profesional_nuevo_membership_id", nullable = false, updatable = false)
	private Long profesionalNuevoMembershipId;

	@Column(name = "motivo", nullable = false, updatable = false, length = 500)
	private String motivo;

	@Column(name = "reemplazado_por_cuenta_id", nullable = false, updatable = false)
	private Long reemplazadoPorCuentaId;

	@Column(name = "reemplazado_en", nullable = false, updatable = false)
	private Instant reemplazadoEn;

	protected SesionReemplazoProfesional() {
		// Requerido por JPA.
	}

	@SuppressWarnings("java:S107")
	public SesionReemplazoProfesional(
			long organizationId,
			long consultorioId,
			long sesionId,
			long profesionalAnteriorMembershipId,
			long profesionalNuevoMembershipId,
			String motivo,
			long reemplazadoPorCuentaId,
			Instant reemplazadoEn) {

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.sesionId = sesionId;
		this.profesionalAnteriorMembershipId = profesionalAnteriorMembershipId;
		this.profesionalNuevoMembershipId = profesionalNuevoMembershipId;
		this.motivo = motivo;
		this.reemplazadoPorCuentaId = reemplazadoPorCuentaId;
		this.reemplazadoEn = reemplazadoEn;
	}

	public Long getId() {
		return id;
	}

	public Long getSesionId() {
		return sesionId;
	}

	public Long getProfesionalAnteriorMembershipId() {
		return profesionalAnteriorMembershipId;
	}

	public Long getProfesionalNuevoMembershipId() {
		return profesionalNuevoMembershipId;
	}

	public String getMotivo() {
		return motivo;
	}

	public Long getReemplazadoPorCuentaId() {
		return reemplazadoPorCuentaId;
	}

	public Instant getReemplazadoEn() {
		return reemplazadoEn;
	}
}
