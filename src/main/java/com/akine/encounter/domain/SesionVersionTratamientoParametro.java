package com.akine.encounter.domain;

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
 * Foto inmutable de un parametro tipado de un tratamiento, en una version de la sesion (C-6).
 *
 * <p>Copia la fila de {@link TratamientoParametro} tal cual: el parametro vivo ya paso por
 * {@code ParametroAplicado#exigirCoherente} y por {@code ck_tratamiento_parametro_valor}, y
 * {@code ck_svtp_valor} repite ese mismo {@code CHECK}.
 */
@Entity
@Table(name = "sesion_version_tratamiento_parametro")
public class SesionVersionTratamientoParametro {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "clave", nullable = false, length = 64, updatable = false)
	private String clave;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo_dato", nullable = false, length = 16, updatable = false)
	private TipoDatoParametro tipoDato;

	@Column(name = "valor_numerico", precision = 12, scale = 3, updatable = false)
	private BigDecimal valorNumerico;

	@Column(name = "valor_texto", length = 280, updatable = false)
	private String valorTexto;

	@Column(name = "valor_booleano", updatable = false)
	private Boolean valorBooleano;

	@Column(name = "unidad", length = 24, updatable = false)
	private String unidad;

	@Column(name = "orden", nullable = false, updatable = false)
	private int orden;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	protected SesionVersionTratamientoParametro() {
		// Requerido por JPA.
	}

	SesionVersionTratamientoParametro(TratamientoParametro vivo, Instant createdAt) {
		this.organizationId = vivo.getOrganizationId();
		this.clave = vivo.getClave();
		this.tipoDato = vivo.getTipoDato();
		this.valorNumerico = vivo.getValorNumerico();
		this.valorTexto = vivo.getValorTexto();
		this.valorBooleano = vivo.getValorBooleano();
		this.unidad = vivo.getUnidad();
		this.orden = vivo.getOrden();
		this.createdAt = createdAt;
	}

	public String getClave() {
		return clave;
	}

	public TipoDatoParametro getTipoDato() {
		return tipoDato;
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

	public String getUnidad() {
		return unidad;
	}

	public int getOrden() {
		return orden;
	}
}
