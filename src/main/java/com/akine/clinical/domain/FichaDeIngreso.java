package com.akine.clinical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * Ficha de ingreso de una Historia Clinica: ocupacion, peso, altura y actividad fisica.
 *
 * <p>A diferencia del antecedente, se edita: el peso cambia, y obligar a dar de baja y volver a
 * cargar seria ruido. La trazabilidad la da la auditoria. Mismo circuito de DP-47: lo que guarda
 * quien declara queda sin revisar (aunque ya estuviera revisado) y lo que guarda un profesional
 * queda revisado por el.
 */
@Entity
@Table(name = "historia_clinica_ficha")
public class FichaDeIngreso extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "historia_clinica_id", nullable = false, updatable = false)
	private Long historiaClinicaId;

	@Column(name = "ocupacion_id")
	private Long ocupacionId;

	@Column(name = "peso_kg", precision = 5, scale = 1)
	private BigDecimal pesoKg;

	@Column(name = "altura_cm", precision = 5, scale = 1)
	private BigDecimal alturaCm;

	@Enumerated(EnumType.STRING)
	@Column(name = "actividad_fisica", length = 16)
	private ActividadFisica actividadFisica;

	@Column(name = "actividad_detalle", length = 120)
	private String actividadDetalle;

	@Enumerated(EnumType.STRING)
	@Column(name = "origen", nullable = false, length = 16)
	private OrigenDato origen;

	@Column(name = "cargado_por", nullable = false)
	private Long cargadoPor;

	@Column(name = "revisado_por")
	private Long revisadoPor;

	@Column(name = "revisado_en")
	private Instant revisadoEn;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected FichaDeIngreso() {
		// Requerido por JPA.
	}

	public FichaDeIngreso(long organizationId, long historiaClinicaId) {
		this.organizationId = organizationId;
		this.historiaClinicaId = historiaClinicaId;
	}

	/** Reemplaza el contenido entero de la ficha y fija su estado de revision segun el origen. */
	public void actualizar(
			Long ocupacionId,
			BigDecimal pesoKg,
			BigDecimal alturaCm,
			ActividadFisica actividad,
			String detalle,
			OrigenDato origen,
			long cuenta,
			Instant ahora) {

		this.ocupacionId = ocupacionId;
		this.pesoKg = pesoKg;
		this.alturaCm = alturaCm;
		this.actividadFisica = actividad;
		this.actividadDetalle = detalle == null || detalle.isBlank() ? null : detalle.strip();
		this.origen = origen;
		this.cargadoPor = cuenta;
		if (origen == OrigenDato.PROFESIONAL) {
			this.revisadoPor = cuenta;
			this.revisadoEn = ahora;
		} else {
			this.revisadoPor = null;
			this.revisadoEn = null;
		}
	}

	/** Marca revisada la ficha. Devuelve {@code false} si ya estaba revisada (idempotente). */
	public boolean revisar(long cuenta, Instant ahora) {
		if (revisadoEn != null) {
			return false;
		}
		this.revisadoPor = cuenta;
		this.revisadoEn = ahora;
		return true;
	}

	/** {@code peso / (altura/100)^2} con un decimal, o null si falta alguno de los dos. */
	public BigDecimal imc() {
		if (pesoKg == null || alturaCm == null || alturaCm.signum() <= 0) {
			return null;
		}
		BigDecimal metros = alturaCm.movePointLeft(2);
		return pesoKg.divide(metros.multiply(metros), 1, RoundingMode.HALF_UP);
	}

	public boolean estaDeclaradaSinRevisar() {
		return origen == OrigenDato.DECLARADO && revisadoEn == null;
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getHistoriaClinicaId() {
		return historiaClinicaId;
	}

	public Long getOcupacionId() {
		return ocupacionId;
	}

	public BigDecimal getPesoKg() {
		return pesoKg;
	}

	public BigDecimal getAlturaCm() {
		return alturaCm;
	}

	public ActividadFisica getActividadFisica() {
		return actividadFisica;
	}

	public String getActividadDetalle() {
		return actividadDetalle;
	}

	public OrigenDato getOrigen() {
		return origen;
	}

	public Long getCargadoPor() {
		return cargadoPor;
	}

	public Long getRevisadoPor() {
		return revisadoPor;
	}

	public Instant getRevisadoEn() {
		return revisadoEn;
	}

	public long getVersion() {
		return version;
	}
}
