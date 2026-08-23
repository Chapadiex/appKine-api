package com.akine.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Registro de idempotencia del alta compuesta de una organizacion (ADR-0008).
 *
 * <p>El alta crea Cuenta + Organizacion + Consultorio + Membership en UNA transaccion. Esta
 * fila guarda el resultado indexado por la clave de idempotencia, para que un reintento
 * devuelva exactamente lo que se creo la primera vez en lugar de crear un segundo tenant.
 *
 * <p>El reintento CONCURRENTE lo resuelve el unique de {@code idempotency_key}, no un chequeo
 * previo: dos hilos con la misma clave leen ambos "no existe", los dos insertan, y el segundo
 * choca contra la restriccion. Ahi se relee la fila y se devuelve el resultado del ganador.
 * Un SELECT previo sin la restriccion detras seria una carrera con otro nombre.
 *
 * <p>{@code idempotencyKey} es unica GLOBAL a proposito: la operacion es pre-tenant, asi que
 * al reintentar la organizacion todavia puede no existir y la clave no puede tener alcance
 * tenant.
 *
 * <p>{@code requestHash} es el SHA-256 del payload canonico y sirve para detectar la misma
 * clave con un contenido distinto —un error del cliente que no puede resolverse devolviendo
 * el resultado viejo—. Es {@code null} cuando el alta entra por {@code spi} y no por HTTP:
 * ahi no hay payload que comparar.
 *
 * <p>Append-only: sin setters.
 */
@Entity
@Table(name = "organization_onboarding")
public class OrganizationOnboarding {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
	private String idempotencyKey;

	/** SHA-256 del payload canonico. {@code null} cuando el alta no vino por HTTP. */
	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	@Column(name = "account_id", nullable = false, updatable = false)
	private Long accountId;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "membership_id", nullable = false, updatable = false)
	private Long membershipId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected OrganizationOnboarding() {
		// Requerido por JPA.
	}

	public OrganizationOnboarding(
			String idempotencyKey,
			String requestHash,
			Long accountId,
			Long organizationId,
			Long consultorioId,
			Long membershipId,
			Instant createdAt) {
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		this.accountId = accountId;
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.membershipId = membershipId;
		this.createdAt = createdAt;
	}

	/**
	 * Indica si el payload del reintento coincide con el del alta original.
	 *
	 * <p>Cuando el registro no guarda hash (alta por spi), no hay nada que contradecir y se
	 * acepta el replay: inventar un conflicto ahi romperia el onboarding self-service.
	 */
	public boolean matchesRequestHash(String otroHash) {
		return requestHash == null || requestHash.equals(otroHash);
	}

	public Long getId() {
		return id;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public Long getAccountId() {
		return accountId;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Long getMembershipId() {
		return membershipId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
