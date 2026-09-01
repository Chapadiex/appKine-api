package com.akine.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Que parte del cobro paga que deuda.
 *
 * <p>Un cobro puede saldar varias obligaciones —el paciente paga tres sesiones juntas— y una
 * obligacion puede recibir varios cobros parciales. Por eso es una tabla propia y no una columna en
 * ninguna de las dos.
 *
 * <p><b>Guarda el importe imputado y no el saldo resultante.</b> El saldo vive en la obligacion y
 * lo modifica un UPDATE condicional; duplicarlo aca habilitaria que las dos versiones discrepen y
 * no habria forma de saber cual es la buena.
 */
@Entity
@Table(name = "cobro_imputacion")
public class CobroImputacion {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/**
	 * Desnormalizado por ADR-0004, que no exceptua a las tablas hijas.
	 *
	 * <p>Se podria llegar al tenant por el cobro, y precisamente por eso la regla existe: una
	 * consulta que se olvide del JOIN cruzaria tenants sin fallar.
	 */
	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "obligacion_id", nullable = false, updatable = false)
	private Long obligacionId;

	@Column(name = "importe", nullable = false, precision = 12, scale = 2, updatable = false)
	private BigDecimal importe;

	protected CobroImputacion() {
		// Requerido por JPA.
	}

	public CobroImputacion(long organizationId, long obligacionId, BigDecimal importe) {
		this.organizationId = organizationId;
		if (importe == null || importe.signum() <= 0) {
			throw new IllegalArgumentException("Una imputacion aplica un importe positivo: " + importe);
		}
		this.obligacionId = obligacionId;
		this.importe = importe;
	}

	public Long getId() {
		return id;
	}

	public Long getObligacionId() {
		return obligacionId;
	}

	public BigDecimal getImporte() {
		return importe;
	}
}
