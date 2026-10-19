package com.akine.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Una liquidacion de honorarios por clase confirmada (AKINE-08.09b, DP-38, RF-M22-006/007).
 *
 * <p>Es la cabecera del snapshot: el egreso que la confirmacion creo, el colaborador, el periodo y
 * el total. El detalle por clase vive en {@link LiquidacionHonorarioClase}. Nace {@code VIGENTE} y
 * pasa a {@code LIBERADA} cuando se anula su egreso, cosa que devuelve sus clases al borrador
 * siguiente. <b>No se borra nunca</b> (regla maestra 10).
 *
 * <p>Esta clase no sabe nada de clases, asistencias ni tarifas: {@code billing} no conoce
 * {@code activity}. Recibe el calculo ya hecho y lo congela.
 */
@Entity
@Table(name = "liquidacion_honorario")
public class LiquidacionHonorario {

	public static final String VIGENTE = "VIGENTE";
	public static final String LIBERADA = "LIBERADA";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "egreso_id", nullable = false, updatable = false)
	private Long egresoId;

	@Column(name = "colaborador_membership_id", nullable = false, updatable = false)
	private Long colaboradorMembershipId;

	@Column(name = "periodo_desde", nullable = false, updatable = false)
	private LocalDate periodoDesde;

	@Column(name = "periodo_hasta", nullable = false, updatable = false)
	private LocalDate periodoHasta;

	@Column(name = "importe_total", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal importeTotal;

	@Column(name = "moneda", nullable = false, updatable = false, length = 3)
	private String moneda;

	@Column(name = "estado", nullable = false, length = 16)
	private String estado;

	@Column(name = "confirmada_en", nullable = false, updatable = false)
	private Instant confirmadaEn;

	@Column(name = "confirmada_por_cuenta_id", nullable = false, updatable = false)
	private Long confirmadaPorCuentaId;

	@Column(name = "liberada_en")
	private Instant liberadaEn;

	@Column(name = "idempotency_key", length = 80, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected LiquidacionHonorario() {
		// Requerido por JPA.
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public LiquidacionHonorario(
			long organizationId,
			long consultorioId,
			long egresoId,
			long colaboradorMembershipId,
			LocalDate periodoDesde,
			LocalDate periodoHasta,
			BigDecimal importeTotal,
			String moneda,
			Instant confirmadaEn,
			long confirmadaPorCuentaId,
			String idempotencyKey,
			String requestHash) {

		if (importeTotal == null || importeTotal.signum() <= 0) {
			throw new IllegalArgumentException("Una liquidacion mueve un importe positivo: " + importeTotal);
		}
		if (periodoDesde.isAfter(periodoHasta)) {
			throw new IllegalArgumentException("El periodo empieza despues de terminar");
		}
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.egresoId = egresoId;
		this.colaboradorMembershipId = colaboradorMembershipId;
		this.periodoDesde = periodoDesde;
		this.periodoHasta = periodoHasta;
		this.importeTotal = importeTotal;
		this.moneda = moneda;
		this.estado = VIGENTE;
		this.confirmadaEn = confirmadaEn;
		this.confirmadaPorCuentaId = confirmadaPorCuentaId;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = idempotencyKey == null ? null : requestHash;
	}

	/** Su egreso se anulo: las clases quedan libres. Idempotente. */
	public void liberar(Instant cuando) {
		if (LIBERADA.equals(estado)) {
			return;
		}
		this.estado = LIBERADA;
		this.liberadaEn = cuando;
	}

	public boolean estaVigente() {
		return VIGENTE.equals(estado);
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

	public Long getEgresoId() {
		return egresoId;
	}

	public Long getColaboradorMembershipId() {
		return colaboradorMembershipId;
	}

	public LocalDate getPeriodoDesde() {
		return periodoDesde;
	}

	public LocalDate getPeriodoHasta() {
		return periodoHasta;
	}

	public BigDecimal getImporteTotal() {
		return importeTotal;
	}

	public String getMoneda() {
		return moneda;
	}

	public String getEstado() {
		return estado;
	}

	public Instant getConfirmadaEn() {
		return confirmadaEn;
	}

	public Long getConfirmadaPorCuentaId() {
		return confirmadaPorCuentaId;
	}

	public Instant getLiberadaEn() {
		return liberadaEn;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public long getVersion() {
		return version;
	}
}
