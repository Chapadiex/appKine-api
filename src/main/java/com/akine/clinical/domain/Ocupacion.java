package com.akine.clinical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;

/**
 * Una ocupacion del catalogo de la ficha de ingreso. Global ({@code organizationId} null) o propia
 * de un centro, con el patron {@code owner_key} de ADR-0021: las lecturas filtran por
 * {@code owner_key IN (0, org)}, nunca por {@code organization_id}.
 */
@Entity
@Table(name = "ocupacion")
public class Ocupacion {

	public static final int LARGO_MAXIMO = 80;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", updatable = false)
	private Long organizationId;

	@Column(name = "nombre", nullable = false, length = LARGO_MAXIMO, updatable = false)
	private String nombre;

	@Column(name = "nombre_normalizado", nullable = false, length = LARGO_MAXIMO, updatable = false)
	private String nombreNormalizado;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected Ocupacion() {
		// Requerido por JPA.
	}

	public Ocupacion(Long organizationId, String nombre) {
		String limpio = nombre == null ? "" : nombre.strip().replaceAll("\\s+", " ");
		if (limpio.isEmpty()) {
			throw new IllegalArgumentException("La ocupacion necesita un nombre");
		}
		if (limpio.length() > LARGO_MAXIMO) {
			throw new IllegalArgumentException("El nombre de la ocupacion supera los 80 caracteres");
		}
		this.organizationId = organizationId;
		this.nombre = limpio;
		this.nombreNormalizado = normalizar(limpio);
		this.createdAt = Instant.now();
	}

	/** Minusculas, sin tildes y con espacios colapsados: igual que el lower(unaccent(..)) del seed. */
	public static String normalizar(String texto) {
		if (texto == null) {
			return "";
		}
		String sinTildes = Normalizer.normalize(texto, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return sinTildes.toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public String getNombre() {
		return nombre;
	}

	public boolean isPropia() {
		return organizationId != null;
	}

	public boolean isActive() {
		return active;
	}
}
