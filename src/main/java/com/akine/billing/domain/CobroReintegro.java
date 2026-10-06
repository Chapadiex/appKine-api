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
import java.time.Instant;

/**
 * Devolucion en dinero de un saldo a favor (F-3; DP-06/ADR-0013; RF-M19-010 paso 6).
 *
 * <h2>No es una reversion del cobro</h2>
 *
 * <p>Una reversion compensa un movimiento entero y una sola vez. Un reintegro puede ser parcial,
 * repetirse y salir por otro medio que el que entro: el paciente dejo 10.000 en efectivo, uso 6.000
 * y se le transfieren 4.000. Por eso es una fila propia y su movimiento de caja es un {@code EGRESO}
 * con origen {@link OrigenMovimiento#REINTEGRO}, no un {@code REVERSION_DE_INGRESO}.
 *
 * <h2>Append-only</h2>
 *
 * <p>Sin {@code version}, sin baja logica y sin metodos de modificacion: es un hecho monetario. Si
 * fue un error, el camino es un ingreso manual de caja (RF-M20-002), que deja las dos filas a la
 * vista. Anular un reintegro no tiene RF.
 */
@Entity
@Table(name = "cobro_reintegro")
public class CobroReintegro {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "cobro_id", nullable = false, updatable = false)
	private Long cobroId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "importe", nullable = false, precision = 12, scale = 2, updatable = false)
	private BigDecimal importe;

	@Column(name = "moneda", nullable = false, length = 3, updatable = false)
	private String moneda;

	@Enumerated(EnumType.STRING)
	@Column(name = "medio", nullable = false, length = 24, updatable = false)
	private MedioDePago medio;

	@Column(name = "referencia", length = 120, updatable = false)
	private String referencia;

	@Column(name = "motivo", nullable = false, length = 280, updatable = false)
	private String motivo;

	@Column(name = "reintegrado_en", nullable = false, updatable = false)
	private Instant reintegradoEn;

	@Column(name = "reintegrado_por_cuenta_id", nullable = false, updatable = false)
	private Long reintegradoPorCuentaId;

	@Column(name = "idempotency_key", length = 80, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	protected CobroReintegro() {
		// Requerido por JPA.
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public CobroReintegro(
			Cobro cobro, BigDecimal importe, MedioDePago medio, String referencia, String motivo,
			Instant reintegradoEn, long reintegradoPorCuentaId,
			String idempotencyKey, String requestHash) {

		if (importe == null || importe.signum() <= 0) {
			throw new IllegalArgumentException("Un reintegro es por un importe positivo: " + importe);
		}
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("Un reintegro exige motivo");
		}
		this.organizationId = cobro.getOrganizationId();
		this.consultorioId = cobro.getConsultorioId();
		this.cobroId = cobro.getId();
		this.personaId = cobro.getPersonaId();
		this.moneda = cobro.getMoneda();
		this.importe = importe;
		this.medio = medio;
		this.referencia = referencia == null || referencia.isBlank() ? null : referencia.trim();
		this.motivo = motivo.trim();
		this.reintegradoEn = reintegradoEn;
		this.reintegradoPorCuentaId = reintegradoPorCuentaId;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
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

	public Long getCobroId() {
		return cobroId;
	}

	public Long getPersonaId() {
		return personaId;
	}

	public BigDecimal getImporte() {
		return importe;
	}

	public String getMoneda() {
		return moneda;
	}

	public MedioDePago getMedio() {
		return medio;
	}

	public String getReferencia() {
		return referencia;
	}

	public String getMotivo() {
		return motivo;
	}

	public Instant getReintegradoEn() {
		return reintegradoEn;
	}

	public Long getReintegradoPorCuentaId() {
		return reintegradoPorCuentaId;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestHash() {
		return requestHash;
	}
}
