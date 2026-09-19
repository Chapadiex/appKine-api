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
 * La participacion de un profesional en un Caso Clinico, con vigencia (RF-M10-005).
 *
 * <h2>El que se va no se borra</h2>
 *
 * <p>Un profesional desvinculado de la organizacion <b>sigue figurando</b> en el equipo del caso
 * que trato, porque lo trato. Sacarlo de la lista reescribiria historia, que es la regla maestra
 * 10. Lo que cambia es que deja de poder escribir, y eso lo decide su membership vigente y
 * {@code hc:write}, no esta fila.
 *
 * <p>Por eso hay {@link #hasta} y no hay {@code DELETE}: la salida del equipo es una fecha, y el
 * historial del caso conserva quien estuvo y cuando.
 *
 * <h2>La membership y no la cuenta</h2>
 *
 * <p>Es lo que identifica al profesional <b>en este centro</b>: la misma persona puede ser
 * profesional en un centro y administrativa en otro. Misma decision que V23 para la
 * disponibilidad y V28 para las habilitaciones.
 */
@Entity
@Table(name = "caso_profesional")
public class CasoProfesional extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "caso_id", nullable = false, updatable = false)
	private Long casoId;

	@Column(name = "profesional_membership_id", nullable = false, updatable = false)
	private Long profesionalMembershipId;

	@Enumerated(EnumType.STRING)
	@Column(name = "rol", nullable = false, length = 16)
	private RolEnCaso rol;

	@Column(name = "desde", nullable = false, updatable = false)
	private Instant desde;

	@Column(name = "hasta")
	private Instant hasta;

	protected CasoProfesional() {
		// Requerido por JPA.
	}

	public CasoProfesional(
			Long organizationId,
			Long casoId,
			Long profesionalMembershipId,
			RolEnCaso rol,
			Instant desde) {

		this.organizationId = organizationId;
		this.casoId = casoId;
		this.profesionalMembershipId = profesionalMembershipId;
		this.rol = rol == null ? RolEnCaso.TRATANTE : rol;
		this.desde = desde;
	}

	/**
	 * Marca la salida del equipo.
	 *
	 * <p>Idempotente: repetirla no mueve la fecha original. La primera salida es la que ocurrio.
	 */
	public void desvincular(Instant occurredAt) {
		if (hasta != null) {
			return;
		}
		this.hasta = occurredAt;
	}

	/** Cambia el rol de alguien que sigue en el equipo. No reabre una participacion terminada. */
	public void cambiarRol(RolEnCaso nuevo) {
		if (hasta != null || nuevo == null) {
			return;
		}
		this.rol = nuevo;
	}

	/** {@code true} mientras la participacion no tenga fecha de salida. */
	public boolean estaVigente() {
		return hasta == null;
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

	public Long getProfesionalMembershipId() {
		return profesionalMembershipId;
	}

	public RolEnCaso getRol() {
		return rol;
	}

	public Instant getDesde() {
		return desde;
	}

	public Instant getHasta() {
		return hasta;
	}
}
