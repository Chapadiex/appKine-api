package com.akine.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Sede de una organizacion, en su forma minima.
 *
 * <p>Existe ya en 01.01 porque el onboarding compuesto crea el primer consultorio en la misma
 * transaccion que la organizacion (ADR-0008) y porque el contexto de trabajo es
 * Organizacion + Consultorio (ADR-0009). La configuracion completa —horarios, espacios,
 * datos fiscales— la EXPANDE la etapa 02.01 (M03) sobre esta misma tabla, sin reemplazarla.
 *
 * <p>{@code UNIQUE(organization_id, name)}: el nombre es unico dentro del tenant, nunca
 * global. Dos organizaciones distintas pueden tener su "Sede Centro" sin colisionar.
 */
@Entity
@Table(name = "consultorio")
public class Consultorio extends TimestampedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "name", nullable = false, length = 160)
	private String name;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Consultorio() {
		// Requerido por JPA.
	}

	public Consultorio(Long organizationId, String name) {
		this.organizationId = organizationId;
		this.name = name;
		this.active = true;
	}

	public void rename(String name) {
		this.name = name;
	}

	/**
	 * Baja logica. Libera cupo del limite MAX_CONSULTORIOS —el conteo solo mira filas
	 * activas— sin invalidar nada de lo que ocurrio en esa sede.
	 */
	public void deactivate(Instant occurredAt) {
		this.active = false;
		this.deletedAt = occurredAt;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public String getName() {
		return name;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public long getVersion() {
		return version;
	}
}
