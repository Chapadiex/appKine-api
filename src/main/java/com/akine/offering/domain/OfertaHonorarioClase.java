package com.akine.offering.domain;

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
import java.util.Currency;
import java.util.Locale;

/**
 * Honorario por clase de una oferta para una vigencia (AKINE-08.09b, DP-38, RF-M22-006).
 *
 * <p>Lo que el centro paga al instructor <b>por cada participante presente</b> (con o sin demora)
 * en una clase de esta oferta. Con varios instructores, el total de la clase se reparte en partes
 * iguales; eso lo calcula {@code activity}, que conoce la clase. Esta fila solo declara la tarifa.
 *
 * <p>Copia deliberada de {@link OfertaPrecioParticular}: el importe no se edita —se cierra la
 * vigencia y se carga otro—, la baja es logica y dos activos de la misma oferta no se solapan,
 * cosa que hace cumplir el servicio bajo el lock de la fila de la oferta (V114).
 */
@Entity
@Table(name = "oferta_honorario_clase")
public class OfertaHonorarioClase extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	@Column(name = "importe_por_participante", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal importePorParticipante;

	@Column(name = "moneda", nullable = false, updatable = false, length = 3)
	private String moneda;

	@Column(name = "vigencia_desde", nullable = false, updatable = false)
	private LocalDate vigenciaDesde;

	@Column(name = "vigencia_hasta")
	private LocalDate vigenciaHasta;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected OfertaHonorarioClase() {
		// Requerido por JPA.
	}

	public OfertaHonorarioClase(
			long organizationId,
			long consultorioId,
			long ofertaId,
			BigDecimal importePorParticipante,
			String moneda,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		if (importePorParticipante == null || importePorParticipante.signum() <= 0) {
			throw new IllegalArgumentException(
					"El honorario por participante tiene que ser mayor que cero");
		}
		if (vigenciaDesde == null) {
			throw new IllegalArgumentException("El honorario necesita fecha de inicio");
		}
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.ofertaId = ofertaId;
		this.importePorParticipante = importePorParticipante;
		this.moneda = exigirMoneda(moneda);
		this.vigenciaDesde = vigenciaDesde;
		aplicarFin(vigenciaHasta);
	}

	/** Cierra (o reabre, con {@code null}) la vigencia. Es lo unico editable. */
	public void cambiarFin(LocalDate hasta) {
		aplicarFin(hasta);
	}

	public void deactivate(Instant occurredAt, String reason) {
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason;
	}

	/** Activo y la fecha cae entre desde y hasta, ambos INCLUSIVE. */
	public boolean aplicaEl(LocalDate fecha) {
		return active && !fecha.isBefore(vigenciaDesde)
				&& (vigenciaHasta == null || !fecha.isAfter(vigenciaHasta));
	}

	/** Dos periodos inclusivos se pisan si cada uno empieza antes de que termine el otro. */
	public boolean seSolapaCon(LocalDate desde, LocalDate hasta) {
		boolean empiezaAntesDelFinDelOtro = hasta == null || !vigenciaDesde.isAfter(hasta);
		boolean otroEmpiezaAntesDeMiFin = vigenciaHasta == null || !desde.isAfter(vigenciaHasta);
		return empiezaAntesDelFinDelOtro && otroEmpiezaAntesDeMiFin;
	}

	public String periodo() {
		return vigenciaDesde + " a " + (vigenciaHasta == null ? "sin fin" : vigenciaHasta.toString());
	}

	private void aplicarFin(LocalDate hasta) {
		if (hasta != null && hasta.isBefore(vigenciaDesde)) {
			throw new IllegalArgumentException(
					"La vigencia del honorario no puede terminar antes de empezar");
		}
		this.vigenciaHasta = hasta;
	}

	private static String exigirMoneda(String moneda) {
		if (moneda == null || moneda.isBlank()) {
			throw new IllegalArgumentException("Un honorario sin moneda no es un importe");
		}
		String codigo = moneda.strip().toUpperCase(Locale.ROOT);
		try {
			Currency.getInstance(codigo);
		} catch (IllegalArgumentException invalida) {
			throw new IllegalArgumentException("La moneda tiene que ser un codigo ISO 4217: " + codigo);
		}
		return codigo;
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

	public BigDecimal getImportePorParticipante() {
		return importePorParticipante;
	}

	public String getMoneda() {
		return moneda;
	}

	public LocalDate getVigenciaDesde() {
		return vigenciaDesde;
	}

	public LocalDate getVigenciaHasta() {
		return vigenciaHasta;
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
