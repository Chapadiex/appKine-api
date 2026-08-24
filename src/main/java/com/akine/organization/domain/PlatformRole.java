package com.akine.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Rol de plataforma: quien puede operar por encima de cualquier tenant.
 *
 * <p><b>Es la unica entidad del modulo sin {@code organizationId}</b>, y la excepcion a
 * ADR-0004 esta formalizada en ADR-0020. El motivo, en una linea: la matriz seccion 1.3 define
 * {@code PLATFORM_ADMIN} como el rol que <b>no tiene membership en ninguna organizacion</b>.
 * Acotarlo a un tenant seria el rol contrario.
 *
 * <p><b>Tener este rol no da acceso a datos de ningun tenant.</b> Abre las rutas de plataforma;
 * para operar DENTRO de una organizacion hace falta ademas un {@link SupportAccess} vigente
 * para esa organizacion, con motivo y vencimiento obligatorios. Son dos tablas y dos
 * decisiones distintas a proposito.
 *
 * <p>El propietario es {@code organization} y no {@code platform}: {@code organization} ya es
 * dueño del catalogo de roles, del catalogo global de planes y del evaluador de permisos, y
 * {@code platform} es el modulo base y no puede depender de ninguno funcional. {@code platform}
 * lo consume por el puerto invertido {@code platform.spi.tenant.PlatformRoleDirectory}.
 *
 * <p>{@code roleCode} admite un unico valor, {@link RoleCode#PLATFORM_ADMIN}: no es una tabla
 * de roles generica, y un {@code CHECK} en la base lo hace cumplir.
 */
@Entity
@Table(name = "platform_role")
public class PlatformRole extends TimestampedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "account_id", nullable = false, updatable = false)
	private Long accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "role_code", nullable = false, length = 48, updatable = false)
	private RoleCode roleCode;

	/** {@code null} = seed de bootstrap: no hubo actor humano. */
	@Column(name = "granted_by_account_id", updatable = false)
	private Long grantedByAccountId;

	@Column(name = "reason", nullable = false, length = 500, updatable = false)
	private String reason;

	@Column(name = "valid_from", nullable = false, updatable = false)
	private Instant validFrom;

	@Column(name = "valid_until")
	private Instant validUntil;

	@Column(name = "revoked_by_account_id")
	private Long revokedByAccountId;

	@Column(name = "revoked_reason", length = 500)
	private String revokedReason;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected PlatformRole() {
		// Requerido por JPA.
	}

	public PlatformRole(Long accountId, Long grantedByAccountId, String reason, Instant validFrom) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"El permiso mas alto del sistema no se otorga sin motivo declarado");
		}
		this.accountId = accountId;
		this.roleCode = RoleCode.PLATFORM_ADMIN;
		this.grantedByAccountId = grantedByAccountId;
		this.reason = reason;
		this.validFrom = validFrom;
		this.active = true;
	}

	/** Vigencia efectiva: baja logica Y ventana temporal. */
	public boolean isValidAt(Instant momento) {
		if (!active || momento.isBefore(validFrom)) {
			return false;
		}
		return validUntil == null || momento.isBefore(validUntil);
	}

	/** Baja logica. La fila nunca se borra: quien tuvo el rol y hasta cuando es auditable. */
	public void revoke(Long revokedByAccountId, String motivo, Instant occurredAt) {
		this.active = false;
		this.deletedAt = occurredAt;
		this.validUntil = occurredAt;
		this.revokedByAccountId = revokedByAccountId;
		this.revokedReason = motivo;
	}

	public Long getId() {
		return id;
	}

	public Long getAccountId() {
		return accountId;
	}

	public RoleCode getRoleCode() {
		return roleCode;
	}

	public Long getGrantedByAccountId() {
		return grantedByAccountId;
	}

	public String getReason() {
		return reason;
	}

	public Instant getValidFrom() {
		return validFrom;
	}

	public Instant getValidUntil() {
		return validUntil;
	}

	public Long getRevokedByAccountId() {
		return revokedByAccountId;
	}

	public String getRevokedReason() {
		return revokedReason;
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
