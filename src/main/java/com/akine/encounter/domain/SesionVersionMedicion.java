package com.akine.encounter.domain;

import com.akine.resource.spi.MedicionTipo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Foto inmutable de una medicion en una version de la sesion (C-6, {@code V71}).
 *
 * <p>Copia la fila de {@link SesionMedicion} con su snapshot de la definicion: la medicion viva
 * puede borrarse o corregirse en una enmienda posterior, y esta foto es lo que conserva el valor
 * de antes.
 */
@Entity
@Table(name = "sesion_version_medicion")
public class SesionVersionMedicion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "definicion_id", nullable = false, updatable = false)
	private Long definicionId;

	@Column(name = "definicion_codigo", nullable = false, length = 48, updatable = false)
	private String definicionCodigo;

	@Column(name = "definicion_nombre", nullable = false, length = 160, updatable = false)
	private String definicionNombre;

	@Column(name = "definicion_unidad", length = 24, updatable = false)
	private String definicionUnidad;

	@Enumerated(EnumType.STRING)
	@Column(name = "definicion_tipo", nullable = false, length = 16, updatable = false)
	private MedicionTipo definicionTipo;

	@Column(name = "definicion_version", nullable = false, updatable = false)
	private long definicionVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "lateralidad", nullable = false, length = 16, updatable = false)
	private LateralidadMedicion lateralidad;

	@Column(name = "valor_numerico", precision = 10, scale = 3, updatable = false)
	private BigDecimal valorNumerico;

	@Column(name = "valor_texto", length = 500, updatable = false)
	private String valorTexto;

	@Column(name = "valor_booleano", updatable = false)
	private Boolean valorBooleano;

	@Column(name = "nota", length = 280, updatable = false)
	private String nota;

	@Column(name = "registrada_en", nullable = false, updatable = false)
	private Instant registradaEn;

	@Column(name = "registrada_por_cuenta_id", nullable = false, updatable = false)
	private Long registradaPorCuentaId;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected SesionVersionMedicion() {
		// Requerido por JPA.
	}

	SesionVersionMedicion(SesionMedicion viva, Instant createdAt) {
		this.organizationId = viva.getOrganizationId();
		this.definicionId = viva.getDefinicionId();
		this.definicionCodigo = viva.getDefinicionCodigo();
		this.definicionNombre = viva.getDefinicionNombre();
		this.definicionUnidad = viva.getDefinicionUnidad();
		this.definicionTipo = viva.getDefinicionTipo();
		this.definicionVersion = viva.getDefinicionVersion();
		this.lateralidad = viva.getLateralidad();
		this.valorNumerico = viva.getValorNumerico();
		this.valorTexto = viva.getValorTexto();
		this.valorBooleano = viva.getValorBooleano();
		this.nota = viva.getNota();
		this.registradaEn = viva.getRegistradaEn();
		this.registradaPorCuentaId = viva.getRegistradaPorCuentaId();
		this.createdAt = createdAt;
	}

	public Long getDefinicionId() {
		return definicionId;
	}

	public String getDefinicionCodigo() {
		return definicionCodigo;
	}

	public String getDefinicionNombre() {
		return definicionNombre;
	}

	public String getDefinicionUnidad() {
		return definicionUnidad;
	}

	public MedicionTipo getDefinicionTipo() {
		return definicionTipo;
	}

	public LateralidadMedicion getLateralidad() {
		return lateralidad;
	}

	public BigDecimal getValorNumerico() {
		return valorNumerico;
	}

	public String getValorTexto() {
		return valorTexto;
	}

	public Boolean getValorBooleano() {
		return valorBooleano;
	}

	public String getNota() {
		return nota;
	}

	public Instant getRegistradaEn() {
		return registradaEn;
	}

	public Long getRegistradaPorCuentaId() {
		return registradaPorCuentaId;
	}
}
