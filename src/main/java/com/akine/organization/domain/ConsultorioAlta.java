package com.akine.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;

/**
 * Registro de idempotencia del alta de una sede adicional (RF-M03-001, CA-M03-001-05).
 *
 * <h2>Por que hacen falta DOS mecanismos y no uno</h2>
 *
 * <ol>
 *   <li>{@code uk_consultorio_org_name_vigente} cierra la carrera de verdad: dos altas
 *       simultaneas con el mismo nombre, una entra y la otra recibe 409.</li>
 *   <li>Esta tabla cubre el reintento por <b>timeout de red</b>, donde el cliente no sabe si la
 *       primera llego. Sin ella, un reintento despues de un 504 con un nombre distinto crearia
 *       dos sedes, y el unique no lo notaria porque los nombres no colisionan.</li>
 * </ol>
 *
 * <p>Es el mismo patron de {@code OrganizationOnboarding} y no otro inventado, con una
 * diferencia deliberada: <b>la clave lleva alcance tenant</b>. El onboarding es PRE-tenant —al
 * reintentar, la organizacion todavia puede no existir— y esto es POST-tenant, asi que ADR-0004
 * aplica sin excepciones.
 *
 * <p>{@code requestHash} es el SHA-256 del payload canonico y detecta la misma clave con un
 * contenido distinto: eso es un error del cliente, no un reintento, y devolverle el resultado
 * viejo lo dejaria creyendo que se creo lo que pidio ahora.
 *
 * <p>Append-only: sin setters y sin baja logica. Una fila de idempotencia no tiene estado.
 */
@Entity
@Table(name = "consultorio_alta")
public class ConsultorioAlta {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "idempotency_key", nullable = false, length = 64, updatable = false)
	private String idempotencyKey;

	/** SHA-256 del payload canonico. {@code null} cuando el alta no vino por HTTP. */
	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected ConsultorioAlta() {
		// Requerido por JPA.
	}

	public ConsultorioAlta(
			Long organizationId,
			String idempotencyKey,
			String requestHash,
			Long consultorioId,
			Instant createdAt) {
		this.organizationId = organizationId;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		this.consultorioId = consultorioId;
		this.createdAt = createdAt;
	}

	/**
	 * Indica si el intento actual corresponde al mismo contenido que el registrado.
	 *
	 * <p>Un registro SIN hash acepta cualquier replay: significa que el alta original no entro
	 * por HTTP y no hay payload con el que comparar. Inventar un conflicto ahi rechazaria un
	 * reintento legitimo.
	 */
	public boolean matchesRequestHash(String candidato) {
		return requestHash == null || Objects.equals(requestHash, candidato);
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}
}
