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
 * Plan comercial del catalogo de la plataforma.
 *
 * <p>Deliberadamente SIN {@code organization_id}: el plan no es un dato de un tenant sino la
 * oferta comercial del SaaS. Darle tenant duplicaria el catalogo entero por organizacion y
 * haria imposible cambiar la oferta en un solo lugar. Es una excepcion documentada a la regla
 * "toda tabla de negocio lleva organization_id", de la misma categoria que
 * {@code platform_schema_info}.
 *
 * <p>Un plan retirado se DESACTIVA, jamas se borra: hay suscripciones historicas que lo
 * referencian y RN-M01-002 prohibe perder esa historia.
 */
@Entity
@Table(name = "plan")
public class Plan extends TimestampedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Clave estable que consumen el seed y el spi de onboarding. Cambiarla romperia ambos. */
	@Column(name = "code", nullable = false, length = 32, updatable = false)
	private String code;

	@Column(name = "name", nullable = false, length = 120)
	private String name;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Plan() {
		// Requerido por JPA.
	}

	public Plan(String code, String name) {
		this.code = code;
		this.name = name;
		this.active = true;
	}

	public void rename(String name) {
		this.name = name;
	}

	/** Retira el plan de la oferta. Las suscripciones que ya lo usan siguen funcionando. */
	public void deactivate(Instant occurredAt) {
		this.active = false;
		this.deletedAt = occurredAt;
	}

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
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
