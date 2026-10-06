package com.akine.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

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
 *
 * <h2>Al cobrar o despues (F-3)</h2>
 *
 * <p>Las imputaciones que nacen con el cobro toman su instante y su actor ({@link #sellar}). Las
 * posteriores —aplicar un anticipo a una deuda que nacio despues— traen los suyos y su propia clave
 * de idempotencia: un reintento no imputa dos veces.
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

	@Column(name = "imputada_en", nullable = false, updatable = false)
	private Instant imputadaEn;

	@Column(name = "imputada_por_cuenta_id", nullable = false, updatable = false)
	private Long imputadaPorCuentaId;

	@Column(name = "idempotency_key", length = 80, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	protected CobroImputacion() {
		// Requerido por JPA.
	}

	/** Una imputacion que nace con su cobro: el cobro la sella al construirse. */
	public CobroImputacion(long organizationId, long obligacionId, BigDecimal importe) {
		this.organizationId = organizationId;
		if (importe == null || importe.signum() <= 0) {
			throw new IllegalArgumentException("Una imputacion aplica un importe positivo: " + importe);
		}
		this.obligacionId = obligacionId;
		this.importe = importe;
	}

	/** Una imputacion posterior del saldo a favor, con su instante, su actor y su clave. */
	public static CobroImputacion posterior(
			long organizationId, long obligacionId, BigDecimal importe,
			Instant imputadaEn, long imputadaPorCuentaId, String idempotencyKey, String requestHash) {

		CobroImputacion imputacion = new CobroImputacion(organizationId, obligacionId, importe);
		imputacion.sellar(imputadaEn, imputadaPorCuentaId);
		imputacion.idempotencyKey = idempotencyKey;
		imputacion.requestHash = requestHash;
		return imputacion;
	}

	/** Fija cuando y quien, si todavia no los tiene. No pisa los de una imputacion posterior. */
	void sellar(Instant cuando, long porCuentaId) {
		if (imputadaEn == null) {
			this.imputadaEn = cuando;
			this.imputadaPorCuentaId = porCuentaId;
		}
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

	public Instant getImputadaEn() {
		return imputadaEn;
	}

	public Long getImputadaPorCuentaId() {
		return imputadaPorCuentaId;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestHash() {
		return requestHash;
	}
}
