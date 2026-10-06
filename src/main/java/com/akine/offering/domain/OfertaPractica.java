package com.akine.offering.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Una practica del catalogo clinico (M06) que una Oferta puede prestar (DP-11, RF-M06-008).
 *
 * <h2>Declara lo que la oferta PUEDE prestar, no lo que se presto</h2>
 *
 * <p>Lo prestado lo dicen los tratamientos realizados de la sesion (06.04). Esta fila sirve para
 * dos cosas: saber que practica devengar o consumir cuando una sesion cierra <b>sin</b>
 * tratamientos —la {@link #isPrincipal() principal}— y detectar como <b>alerta</b> una practica
 * realizada que la oferta no declara. Nunca para rechazar un acto clinico (DP-11).
 *
 * <h2>Una sola principal vigente por oferta, y la sostiene la base</h2>
 *
 * <p>{@code uk_oferta_practica_principal} sobre la columna generada {@code principal_key} (V75).
 * La aplicacion igual respeta el orden de escritura —primero desmarcar, despues marcar—, porque
 * Hibernate ejecuta los INSERT antes que los UPDATE y el reemplazo legitimo chocaria contra su
 * propio unique. Ver {@code OfertaPracticaService}.
 *
 * <p>Las columnas generadas {@code deleted_key} y {@code principal_key} no se mapean: las calcula
 * MySQL y nadie las lee desde Java.
 */
@Entity
@Table(name = "oferta_practica")
public class OfertaPractica extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	@Column(name = "practica_id", nullable = false, updatable = false)
	private Long practicaId;

	@Column(name = "principal", nullable = false)
	private boolean principal;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected OfertaPractica() {
		// Requerido por JPA.
	}

	public OfertaPractica(
			Long organizationId, Long consultorioId, Long ofertaId, Long practicaId, boolean principal) {

		this.organizationId = exigir(organizationId, "La practica de una oferta exige organizacion");
		this.consultorioId = exigir(consultorioId, "La practica de una oferta exige sede");
		this.ofertaId = exigir(ofertaId, "La practica de una oferta exige la oferta");
		this.practicaId = exigir(practicaId, "La practica de una oferta exige la practica");
		this.principal = principal;
	}

	/**
	 * Baja logica con motivo. Una fila dada de baja <b>deja de ser principal</b>: lo exige
	 * {@code ck_oferta_practica_principal_vigente}, y una principal que ya no se ofrece no puede ser
	 * el defecto de nada.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de una practica de la oferta exige un motivo declarado");
		}
		this.active = false;
		this.principal = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	public void marcarPrincipal() {
		if (!isOperable()) {
			throw new IllegalStateException("Una practica dada de baja no puede ser la principal");
		}
		this.principal = true;
	}

	public void desmarcarPrincipal() {
		this.principal = false;
	}

	public boolean isOperable() {
		return active && deletedAt == null;
	}

	private static Long exigir(Long valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
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

	public Long getOfertaId() {
		return ofertaId;
	}

	public Long getPracticaId() {
		return practicaId;
	}

	public boolean isPrincipal() {
		return principal;
	}

	public boolean isActive() {
		return active;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}

	public String getDeactivationReason() {
		return deactivationReason;
	}

	public long getVersion() {
		return version;
	}
}
