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
 * Precio particular de una oferta para una vigencia (B-3, RF-M16-009, RN-M16-007).
 *
 * <p>{@code precio_base} de la oferta sigue siendo el precio de lista sin vigencia. Esta fila fija
 * un precio ESPECIFICO para un periodo: el dia que cubre, manda sobre {@code precio_base}.
 *
 * <h2>El importe no se edita</h2>
 *
 * <p>Lo unico editable es el fin de la vigencia, para cerrar el precio actual el dia antes de que
 * rija el nuevo. Corregir un importe es darlo de baja y cargarlo de nuevo. Lo ya devengado no se
 * toca nunca, porque la obligacion copio su importe (07.01, CA-M16-009-06), pero un importe que
 * cambia en el lugar borra la explicacion de por que se cobro lo que se cobro.
 *
 * <h2>Dos precios activos de la misma oferta no se solapan</h2>
 *
 * <p>Lo hace cumplir {@code OfertaPrecioParticularService} bajo el lock de la fila de la oferta,
 * nunca un indice: dos periodos que se cruzan no comparten ningun valor de columna (V43, V80).
 */
@Entity
@Table(name = "oferta_precio_particular")
public class OfertaPrecioParticular extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	@Column(name = "importe", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal importe;

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

	protected OfertaPrecioParticular() {
		// Requerido por JPA.
	}

	public OfertaPrecioParticular(
			long organizationId,
			long consultorioId,
			long ofertaId,
			BigDecimal importe,
			String moneda,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		if (importe == null) {
			throw new IllegalArgumentException("El precio particular necesita un importe");
		}
		if (importe.signum() < 0) {
			throw new IllegalArgumentException("El precio particular no puede ser negativo");
		}
		if (vigenciaDesde == null) {
			throw new IllegalArgumentException("El precio particular necesita fecha de inicio");
		}
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.ofertaId = ofertaId;
		this.importe = importe;
		this.moneda = exigirMoneda(moneda);
		this.vigenciaDesde = vigenciaDesde;
		aplicarFin(vigenciaHasta);
	}

	/** Cierra (o reabre, con {@code null}) la vigencia. Es lo unico editable: ver la cabecera. */
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
					"La vigencia del precio no puede terminar antes de empezar");
		}
		this.vigenciaHasta = hasta;
	}

	private static String exigirMoneda(String moneda) {
		if (moneda == null || moneda.isBlank()) {
			throw new IllegalArgumentException("Un precio sin moneda no es un precio");
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

	public BigDecimal getImporte() {
		return importe;
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
