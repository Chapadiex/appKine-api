package com.akine.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;

/**
 * Acceso temporal de un {@code PLATFORM_ADMIN} a un tenant concreto.
 *
 * <p>Es la unica via por la que el rol de plataforma toca datos de una organizacion (matriz
 * seccion 7, ADR-0020). Sin una fila vigente aca, un {@code PLATFORM_ADMIN} puede administrar
 * la plataforma y no puede ver nada de adentro de un tenant.
 *
 * <p><b>{@code validUntil} es obligatorio y esa es toda la diferencia con un grant comun.</b>
 * La matriz seccion 3 define "Soporte" como "justificacion obligatoria, acotado en tiempo y
 * auditado": un acceso de soporte sin fin no es soporte. La columna {@code NOT NULL} convierte
 * ese "acotado en tiempo" en un invariante de la base y no en una intencion del codigo.
 *
 * <p>Decision del usuario del 23/08/2026 (D-3): lo concede el propio {@code PLATFORM_ADMIN} con
 * motivo declarado, dura {@link #VIGENCIA_POR_DEFECTO} y queda auditado. Cada operacion
 * amparada por el deja ademas {@code SUPPORT_ACCESS_USED}.
 */
@Entity
@Table(name = "support_access")
public class SupportAccess extends TimestampedEntity {

	/**
	 * Cuatro horas.
	 *
	 * <p>Es la decision del usuario entre las tres opciones evaluadas. Una hora genera friccion
	 * operativa real —un incidente de soporte rara vez se cierra en una— y veinticuatro deja
	 * abierta una ventana de un dia entero sobre datos de salud ajenos.
	 */
	public static final Duration VIGENCIA_POR_DEFECTO = Duration.ofHours(4);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "account_id", nullable = false, updatable = false)
	private Long accountId;

	@Column(name = "reason", nullable = false, length = 500, updatable = false)
	private String reason;

	@Column(name = "granted_by_account_id", nullable = false, updatable = false)
	private Long grantedByAccountId;

	@Column(name = "valid_from", nullable = false, updatable = false)
	private Instant validFrom;

	@Column(name = "valid_until", nullable = false)
	private Instant validUntil;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "revoked_by_account_id")
	private Long revokedByAccountId;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected SupportAccess() {
		// Requerido por JPA.
	}

	public SupportAccess(
			Long organizationId,
			Long accountId,
			String reason,
			Long grantedByAccountId,
			Instant validFrom,
			Instant validUntil) {

		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"El acceso de soporte exige un motivo declarado (matriz seccion 3)");
		}
		if (validUntil == null || !validUntil.isAfter(validFrom)) {
			throw new IllegalArgumentException(
					"El acceso de soporte SIEMPRE vence, y su vencimiento es posterior a su inicio");
		}
		this.organizationId = organizationId;
		this.accountId = accountId;
		this.reason = reason;
		this.grantedByAccountId = grantedByAccountId;
		this.validFrom = validFrom;
		this.validUntil = validUntil;
		this.active = true;
	}

	/** Vigencia efectiva: baja logica, revocacion anticipada Y ventana temporal. */
	public boolean isValidAt(Instant momento) {
		if (!active || revokedAt != null || momento.isBefore(validFrom)) {
			return false;
		}
		return momento.isBefore(validUntil);
	}

	/** Revocacion anticipada. No borra: cierra. */
	public void revoke(Long revokedByAccountId, Instant occurredAt) {
		this.revokedAt = occurredAt;
		this.revokedByAccountId = revokedByAccountId;
		this.active = false;
		this.deletedAt = occurredAt;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getAccountId() {
		return accountId;
	}

	public String getReason() {
		return reason;
	}

	public Long getGrantedByAccountId() {
		return grantedByAccountId;
	}

	public Instant getValidFrom() {
		return validFrom;
	}

	public Instant getValidUntil() {
		return validUntil;
	}

	public Instant getRevokedAt() {
		return revokedAt;
	}

	public Long getRevokedByAccountId() {
		return revokedByAccountId;
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
