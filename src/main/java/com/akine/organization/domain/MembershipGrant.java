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
 * Permiso adicional otorgado a una membership concreta.
 *
 * <p>Materializa los valores "No por defecto" y "Segun permiso" de la matriz §3: lo que no
 * forma parte del rol pero puede otorgarse explicitamente a una persona, con autor, motivo y
 * vigencia. Es la contracara de {@link RolePermissions}, que es la asignacion base y vive en
 * codigo porque es la especificacion.
 *
 * <p>{@code reason} es obligatorio y no es burocracia: un permiso adicional sin motivo
 * declarado no se puede revisar seis meses despues, que es exactamente cuando se revisa.
 *
 * <p>La baja es logica ({@code active = 0}) y el unique de la tabla usa una columna generada
 * que vale {@code NULL} cuando el grant no esta vigente. Asi hay a lo sumo un grant activo por
 * permiso y por membership, <b>conservando el historial</b> de los revocados: un unique sobre
 * {@code permission_code} a secas impediria re-otorgar lo que alguna vez se quito.
 */
@Entity
@Table(name = "membership_grant")
public class MembershipGrant extends TimestampedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "membership_id", nullable = false, updatable = false)
	private Long membershipId;

	@Enumerated(EnumType.STRING)
	@Column(name = "permission_code", nullable = false, length = 48, updatable = false)
	private PermissionCode permissionCode;

	@Column(name = "granted_by_account_id", nullable = false, updatable = false)
	private Long grantedByAccountId;

	@Column(name = "reason", nullable = false, length = 500, updatable = false)
	private String reason;

	@Column(name = "valid_from", nullable = false, updatable = false)
	private Instant validFrom;

	/** {@code null} = sin fin. */
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

	protected MembershipGrant() {
		// Requerido por JPA.
	}

	public MembershipGrant(
			Long organizationId,
			Long membershipId,
			PermissionCode permissionCode,
			Long grantedByAccountId,
			String reason,
			Instant validFrom,
			Instant validUntil) {

		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"Un permiso adicional exige un motivo declarado: sin el no se puede revisar");
		}
		this.organizationId = organizationId;
		this.membershipId = membershipId;
		this.permissionCode = permissionCode;
		this.grantedByAccountId = grantedByAccountId;
		this.reason = reason;
		this.validFrom = validFrom;
		this.validUntil = validUntil;
		this.active = true;
	}

	/**
	 * Indica si el grant habilita algo en ese instante.
	 *
	 * <p>Baja logica Y ventana temporal, igual que {@link Membership#isValidAt(Instant)}: son
	 * condiciones independientes y las dos tienen que cumplirse.
	 */
	public boolean isValidAt(Instant momento) {
		if (!active || momento.isBefore(validFrom)) {
			return false;
		}
		return validUntil == null || momento.isBefore(validUntil);
	}

	/** Baja logica del grant: cierra vigencia y deja constancia de quien y por que. */
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

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getMembershipId() {
		return membershipId;
	}

	public PermissionCode getPermissionCode() {
		return permissionCode;
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
