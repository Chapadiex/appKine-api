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
 * El tenant. Su {@code id} ES el {@code organization_id} del resto del sistema, y por eso
 * esta es la unica entidad de negocio que no lleva esa columna: es la raiz del tenant, no un
 * dato dentro de uno.
 *
 * <p><b>Sin columna de estado.</b> La organizacion no tiene maquina de estados propia: solo
 * baja logica. El estado operativo del tenant deriva de su suscripcion (ver
 * {@link OperationalStatus}). Duplicarlo en dos entidades habilita la contradiccion
 * "organizacion activa con suscripcion cancelada", que nadie sabria resolver.
 *
 * <p>{@code timezone} es una zona IANA explicita porque las reglas locales —cierre de dia,
 * agenda, vencimientos— no se pueden calcular sobre UTC ni sobre la zona del servidor.
 */
@Entity
@Table(name = "organization")
public class Organization extends TimestampedEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "name", nullable = false, length = 160)
	private String name;

	/** Identificador legible, unico GLOBAL: es lo que distingue un tenant de otro. */
	@Column(name = "slug", nullable = false, length = 64, updatable = false)
	private String slug;

	@Column(name = "timezone", nullable = false, length = 64)
	private String timezone;

	// --- Datos fiscales del tenant (V140). Los heredan las sedes sin datos fiscales propios ---

	@Column(name = "legal_name", length = 200)
	private String legalName;

	@Column(name = "tax_id", length = 32)
	private String taxId;

	@Column(name = "fiscal_address_line", length = 240)
	private String fiscalAddressLine;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	/** Locking optimista: dos ediciones concurrentes no pueden pisarse en silencio. */
	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected Organization() {
		// Requerido por JPA.
	}

	public Organization(String name, String slug, String timezone) {
		this.name = name;
		this.slug = slug;
		this.timezone = timezone;
		this.active = true;
	}

	/** Cambia los datos editables. El slug no: se usa en URLs y en soporte, y renombrarlo rompe enlaces. */
	public void update(String name, String timezone) {
		this.name = name;
		this.timezone = timezone;
	}

	/**
	 * Datos fiscales del tenant, PATCH: {@code null} deja lo que estaba y la cadena vacia borra,
	 * igual que los datos institucionales de la sede ({@code Consultorio.updateDatos}).
	 */
	public void updateDatosFiscales(String legalName, String taxId, String fiscalAddressLine) {
		if (legalName != null) {
			this.legalName = vacioEsNulo(legalName);
		}
		if (taxId != null) {
			this.taxId = vacioEsNulo(taxId);
		}
		if (fiscalAddressLine != null) {
			this.fiscalAddressLine = vacioEsNulo(fiscalAddressLine);
		}
	}

	private static String vacioEsNulo(String valor) {
		return valor.isBlank() ? null : valor;
	}

	/**
	 * Baja logica. Nunca hay DELETE fisico: la organizacion es el ancla de historia clinica,
	 * obligaciones y auditoria (regla maestra 10).
	 */
	public void deactivate(Instant occurredAt) {
		this.active = false;
		this.deletedAt = occurredAt;
	}

	public Long getId() {
		return id;
	}

	public String getName() {
		return name;
	}

	public String getSlug() {
		return slug;
	}

	public String getTimezone() {
		return timezone;
	}

	public String getLegalName() {
		return legalName;
	}

	public String getTaxId() {
		return taxId;
	}

	public String getFiscalAddressLine() {
		return fiscalAddressLine;
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
