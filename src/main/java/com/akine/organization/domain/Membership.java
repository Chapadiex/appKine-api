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
 * Vinculo contextual entre una cuenta y una organizacion (RF-M01-002).
 *
 * <p>La separacion es estricta y no se puede colapsar: identidad global (una cuenta) no es lo
 * mismo que membership contextual (esta entidad) ni que habilitacion profesional (M27). Una
 * misma persona puede trabajar en dos organizaciones con roles distintos.
 *
 * <p>{@code accountId} es una referencia LOGICA al modulo {@code identity}: sin FK fisica,
 * porque cada tabla tiene un unico modulo propietario. La direccion permitida es
 * {@code identity -> organization.spi}; {@code organization} nunca compila contra
 * {@code identity}.
 *
 * <p>{@code consultorioId} en {@code null} significa alcance ORGANIZACION: la membership vale
 * para todas las sedes. La columna nace ya en 01.01 aunque 01.01 solo escriba {@code null},
 * para que 01.03 expanda sin renombrar nada (ADR-0007 prohibe el rename disfrazado).
 *
 * <p>{@code isFounder} es un ATRIBUTO, no un rol. El rol del propietario es
 * {@link RoleCode#ORG_ADMIN}, valor de la matriz aprobada; crear un rol "OWNER" para
 * distinguirlo estaria prohibido por RN-M05-006. 01.03 usa este atributo para el invariante
 * "el fundador no puede ser desvinculado por otro admin", que es distinto del invariante
 * "no se puede dejar la organizacion sin admin".
 */
@Entity
@Table(name = "membership")
public class Membership extends TimestampedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/** {@code null} = la membership alcanza a toda la organizacion. */
	@Column(name = "consultorio_id")
	private Long consultorioId;

	@Column(name = "account_id", nullable = false, updatable = false)
	private Long accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "role_code", nullable = false, length = 48)
	private RoleCode roleCode;

	@Column(name = "is_founder", nullable = false)
	private boolean founder;

	@Column(name = "valid_from", nullable = false)
	private Instant validFrom;

	/** {@code null} = sin fin. */
	@Column(name = "valid_until")
	private Instant validUntil;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Membership() {
		// Requerido por JPA.
	}

	public Membership(
			Long organizationId,
			Long consultorioId,
			Long accountId,
			RoleCode roleCode,
			boolean founder,
			Instant validFrom) {
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.accountId = accountId;
		this.roleCode = roleCode;
		this.founder = founder;
		this.validFrom = validFrom;
		this.active = true;
	}

	/**
	 * Indica si la membership esta vigente en un instante dado.
	 *
	 * <p>Vigencia y baja logica son cosas distintas y las dos tienen que cumplirse: una
	 * membership puede estar activa pero con la vigencia vencida, y en ese caso no habilita
	 * ningun contexto. Esta pregunta se responde en CADA request, sin cache: por eso una
	 * revocacion deja de servir en el request siguiente, sin ventana de gracia.
	 */
	public boolean isValidAt(Instant momento) {
		if (!active) {
			return false;
		}
		if (momento.isBefore(validFrom)) {
			return false;
		}
		return validUntil == null || momento.isBefore(validUntil);
	}

	/**
	 * Indica si la membership alcanza a toda la organizacion.
	 *
	 * <p>{@code consultorio_id} nulo significa "todas las sedes"; con valor significa esa sede
	 * y ninguna otra. Son dos ALCANCES distintos, no dos formas de escribir lo mismo, y desde
	 * la migracion V10 una cuenta puede tener las dos cosas a la vez en la misma organizacion.
	 */
	public boolean isOrganizationScoped() {
		return consultorioId == null;
	}

	/**
	 * Indica si la membership habilita trabajar en una sede concreta.
	 *
	 * <p>No mira la vigencia: alcance y vigencia son condiciones independientes y las dos
	 * tienen que cumplirse. Quien decide combina esto con {@link #isValidAt(Instant)}.
	 */
	public boolean covers(long consultorioId) {
		return this.consultorioId == null || this.consultorioId == consultorioId;
	}

	public void changeRole(RoleCode roleCode) {
		this.roleCode = roleCode;
	}

	/** Cierra la vigencia sin borrar la fila: el vinculo historico se conserva. */
	public void endValidity(Instant validUntil) {
		this.validUntil = validUntil;
	}

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

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Long getAccountId() {
		return accountId;
	}

	public RoleCode getRoleCode() {
		return roleCode;
	}

	public boolean isFounder() {
		return founder;
	}

	public Instant getValidFrom() {
		return validFrom;
	}

	public Instant getValidUntil() {
		return validUntil;
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
