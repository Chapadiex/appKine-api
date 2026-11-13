package com.akine.organization.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Logo del tenant para un tema. <b>Solo lectura desde JPA</b>: se escribe con un upsert nativo
 * ({@code OrganizationLogoRepositoryPort#guardar}), que no tiene carrera entre dos primeras
 * subidas simultaneas. Por eso no hay constructor publico ni setters.
 */
@Entity
@Table(name = "organization_logo")
public class OrganizationLogo {

	@Id
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Enumerated(EnumType.STRING)
	@Column(name = "variante", nullable = false, length = 8, updatable = false)
	private LogoVariante variante;

	@Column(name = "content_type", nullable = false, length = 32)
	private String contentType;

	@Column(name = "contenido", nullable = false)
	private byte[] contenido;

	@Column(name = "sha256", nullable = false, length = 64)
	private String sha256;

	protected OrganizationLogo() {
		// Requerido por JPA.
	}

	public LogoVariante getVariante() {
		return variante;
	}

	public String getContentType() {
		return contentType;
	}

	public byte[] getContenido() {
		return contenido;
	}

	public String getSha256() {
		return sha256;
	}
}
