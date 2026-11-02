package com.akine.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Constancia de que una cuenta acepto una version de los terminos (DP-45).
 *
 * <p>Append-only: una fila por cuenta y version. Es una tabla y no dos columnas en
 * {@code cuenta} porque cuando cambie la version habra re-aceptaciones, y la historia de que
 * acepto cada persona y cuando no se puede pisar. Sin {@code organization_id}: la cuenta es una
 * identidad global (ADR-0019) y los terminos se aceptan como persona, no como tenant.
 */
@Entity
@Table(name = "aceptacion_terminos")
public class AceptacionTerminos {

	/** Por que camino nacio la cuenta que acepto. */
	public enum Origen {
		REGISTRO,
		INVITACION
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "cuenta_id", nullable = false, updatable = false)
	private Long cuentaId;

	@Column(name = "version", nullable = false, length = TerminosSettings.VERSION_MAX,
			updatable = false)
	private String version;

	@Column(name = "aceptado_en", nullable = false, updatable = false)
	private Instant aceptadoEn;

	@Enumerated(EnumType.STRING)
	@Column(name = "origen", nullable = false, length = 20, updatable = false)
	private Origen origen;

	protected AceptacionTerminos() {
		// Requerido por JPA.
	}

	public AceptacionTerminos(Long cuentaId, String version, Instant aceptadoEn, Origen origen) {
		this.cuentaId = cuentaId;
		this.version = version;
		this.aceptadoEn = aceptadoEn;
		this.origen = origen;
	}

	public Long getId() {
		return id;
	}

	public Long getCuentaId() {
		return cuentaId;
	}

	public String getVersion() {
		return version;
	}

	public Instant getAceptadoEn() {
		return aceptadoEn;
	}

	public Origen getOrigen() {
		return origen;
	}
}
