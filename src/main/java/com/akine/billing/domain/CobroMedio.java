package com.akine.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * Por que via entro una parte del cobro.
 *
 * <p>Un cobro puede tener varios: pagar mitad en efectivo y mitad con tarjeta es normal en un
 * centro. Por eso es una tabla y no un par de columnas en {@code cobro}.
 *
 * @param referencia numero de operacion, ultimos digitos, lo que el operador anote. Texto libre
 *                   porque cada medio tiene la suya, y darle estructura obligaria a una tabla por
 *                   medio de pago
 */
@Entity
@Table(name = "cobro_medio")
public class CobroMedio {

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

	@Enumerated(EnumType.STRING)
	@Column(name = "medio", nullable = false, length = 24, updatable = false)
	private MedioDePago medio;

	@Column(name = "importe", nullable = false, precision = 12, scale = 2, updatable = false)
	private BigDecimal importe;

	@Column(name = "referencia", length = 120, updatable = false)
	private String referencia;

	protected CobroMedio() {
		// Requerido por JPA.
	}

	public CobroMedio(long organizationId, MedioDePago medio, BigDecimal importe, String referencia) {
		this.organizationId = organizationId;
		if (importe == null || importe.signum() <= 0) {
			throw new IllegalArgumentException("Un medio de pago aporta un importe positivo: " + importe);
		}
		this.medio = medio;
		this.importe = importe;
		this.referencia = referencia == null || referencia.isBlank() ? null : referencia.trim();
	}

	public Long getId() {
		return id;
	}

	public MedioDePago getMedio() {
		return medio;
	}

	public BigDecimal getImporte() {
		return importe;
	}

	public String getReferencia() {
		return referencia;
	}
}
