package com.akine.person.domain;

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
 * Una alerta sobre una autorizacion que nace de un hecho de otro modulo (DP-13, RN-M17-003).
 *
 * <p><b>Solo lectura desde JPA.</b> Se inserta con un {@code INSERT ... ON DUPLICATE KEY UPDATE}
 * nativo —una alerta por consumo, y dos anulaciones concurrentes no chocan— y se resuelve con un
 * {@code UPDATE} condicional. Ninguna de las dos escrituras pasa por la sesion de JPA, y por eso
 * todas las columnas son {@code insertable = false, updatable = false}: si alguien la guardara por
 * JPA, Hibernate no tendria nada que escribir.
 */
@Entity
@Table(name = "autorizacion_alerta")
public class AutorizacionAlerta {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", insertable = false, updatable = false)
	private Long organizationId;

	@Column(name = "autorizacion_id", insertable = false, updatable = false)
	private Long autorizacionId;

	@Column(name = "persona_id", insertable = false, updatable = false)
	private Long personaId;

	@Column(name = "movimiento_id", insertable = false, updatable = false)
	private Long movimientoId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", insertable = false, updatable = false, length = 24)
	private TipoAlertaAutorizacion tipo;

	@Column(name = "sesion_id", insertable = false, updatable = false)
	private Long sesionId;

	@Column(name = "obligacion_id", insertable = false, updatable = false)
	private Long obligacionId;

	@Column(name = "motivo_origen", insertable = false, updatable = false, length = 280)
	private String motivoOrigen;

	@Column(name = "generada_en", insertable = false, updatable = false)
	private Instant generadaEn;

	@Column(name = "generada_por", insertable = false, updatable = false)
	private Long generadaPor;

	@Column(name = "resuelta_en", insertable = false, updatable = false)
	private Instant resueltaEn;

	@Column(name = "resuelta_por", insertable = false, updatable = false)
	private Long resueltaPor;

	@Enumerated(EnumType.STRING)
	@Column(name = "resolucion", insertable = false, updatable = false, length = 24)
	private ResolucionAlertaAutorizacion resolucion;

	protected AutorizacionAlerta() {
		// Requerido por JPA.
	}

	public boolean pendiente() {
		return resueltaEn == null;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getAutorizacionId() {
		return autorizacionId;
	}

	public Long getPersonaId() {
		return personaId;
	}

	public Long getMovimientoId() {
		return movimientoId;
	}

	public TipoAlertaAutorizacion getTipo() {
		return tipo;
	}

	public Long getSesionId() {
		return sesionId;
	}

	public Long getObligacionId() {
		return obligacionId;
	}

	public String getMotivoOrigen() {
		return motivoOrigen;
	}

	public Instant getGeneradaEn() {
		return generadaEn;
	}

	public Long getGeneradaPor() {
		return generadaPor;
	}

	public Instant getResueltaEn() {
		return resueltaEn;
	}

	public Long getResueltaPor() {
		return resueltaPor;
	}

	public ResolucionAlertaAutorizacion getResolucion() {
		return resolucion;
	}
}
