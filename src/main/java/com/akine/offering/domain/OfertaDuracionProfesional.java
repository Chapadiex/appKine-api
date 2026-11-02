package com.akine.offering.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Cuanto tarda un profesional en una oferta, cuando no es lo que dura la oferta (TURNO - Tecnico §47).
 *
 * <p><b>No es una habilitacion, y por eso no extiende {@link Habilitacion}.</b> Una fila de habilitacion
 * restringe la oferta —lista vacia significa todos— y una de estas no restringe nada: Carla puede tardar
 * 30 minutos en una oferta que cualquiera puede prestar. Tampoco lleva vigencia: el turno copia su fin
 * al reservarse, asi que el pasado no necesita saber cuanto duraba esto en marzo.
 */
@Entity
@Table(name = "oferta_duracion_profesional")
public class OfertaDuracionProfesional extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false)
	private Long consultorioId;

	@Column(name = "oferta_id", nullable = false)
	private Long ofertaId;

	@Column(name = "membership_id", nullable = false)
	private Long membershipId;

	@Column(name = "duracion_minutos", nullable = false)
	private int duracionMinutos;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected OfertaDuracionProfesional() {
		// Requerido por JPA.
	}

	public OfertaDuracionProfesional(
			long organizationId, long consultorioId, long ofertaId, long membershipId, int duracionMinutos) {

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.ofertaId = ofertaId;
		this.membershipId = membershipId;
		cambiarDuracion(duracionMinutos);
	}

	public void cambiarDuracion(int minutos) {
		if (minutos < 1) {
			throw new IllegalArgumentException("La duracion propia de un profesional tiene que ser mayor a cero");
		}
		this.duracionMinutos = minutos;
	}

	/** Quitarla no borra: cierra, y el profesional vuelve a la duracion de la oferta. */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException("La baja de una duracion propia exige un motivo declarado");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
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

	public Long getMembershipId() {
		return membershipId;
	}

	public int getDuracionMinutos() {
		return duracionMinutos;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public String getDeactivationReason() {
		return deactivationReason;
	}

	public long getVersion() {
		return version;
	}
}
