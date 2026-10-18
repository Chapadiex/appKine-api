package com.akine.billing.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Una clase de una liquidacion de honorarios: el snapshot de lo que explica el importe
 * (AKINE-08.09b, RF-M22-007, CA-M22-007-06).
 *
 * <p>Todas sus columnas son {@code updatable = false} salvo {@code liberada_key}, que la mueve un
 * {@code UPDATE} nativo al anular el egreso (ver {@code LiquidacionHonorarioRepositoryPort}). Ese
 * valor es el candado contra la doble liquidacion: {@code 0} mientras esta vigente, el propio id
 * al liberarse, y un unique sobre {@code (organization_id, clase_id, colaborador, liberada_key)}.
 */
@Entity
@Table(name = "liquidacion_honorario_clase")
public class LiquidacionHonorarioClase {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "liquidacion_id", nullable = false, updatable = false)
	private Long liquidacionId;

	@Column(name = "clase_id", nullable = false, updatable = false)
	private Long claseId;

	@Column(name = "colaborador_membership_id", nullable = false, updatable = false)
	private Long colaboradorMembershipId;

	@Column(name = "clase_inicio", nullable = false, updatable = false)
	private Instant claseInicio;

	@Column(name = "clase_fecha", nullable = false, updatable = false)
	private LocalDate claseFecha;

	@Column(name = "clase_titulo", length = 120, updatable = false)
	private String claseTitulo;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	@Column(name = "oferta_nombre", nullable = false, length = 160, updatable = false)
	private String ofertaNombre;

	@Column(name = "presentes", nullable = false, updatable = false)
	private int presentes;

	@Column(name = "honorario_id", nullable = false, updatable = false)
	private Long honorarioId;

	@Column(name = "importe_por_participante", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal importePorParticipante;

	@Column(name = "importe_clase", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal importeClase;

	@Column(name = "instructores", nullable = false, updatable = false)
	private int instructores;

	@Column(name = "posicion", nullable = false, updatable = false)
	private int posicion;

	@Column(name = "importe_colaborador", nullable = false, updatable = false, precision = 12, scale = 2)
	private BigDecimal importeColaborador;

	@Column(name = "moneda", nullable = false, length = 3, updatable = false)
	private String moneda;

	/** Lo mueve un UPDATE nativo, nunca esta clase. */
	@Column(name = "liberada_key", nullable = false, insertable = false, updatable = false)
	private long liberadaKey;

	protected LiquidacionHonorarioClase() {
		// Requerido por JPA.
	}

	@SuppressWarnings("checkstyle:ParameterNumber")
	public LiquidacionHonorarioClase(
			long organizationId,
			long liquidacionId,
			long claseId,
			long colaboradorMembershipId,
			Instant claseInicio,
			LocalDate claseFecha,
			String claseTitulo,
			long ofertaId,
			String ofertaNombre,
			int presentes,
			long honorarioId,
			BigDecimal importePorParticipante,
			BigDecimal importeClase,
			int instructores,
			int posicion,
			BigDecimal importeColaborador,
			String moneda) {

		this.organizationId = organizationId;
		this.liquidacionId = liquidacionId;
		this.claseId = claseId;
		this.colaboradorMembershipId = colaboradorMembershipId;
		this.claseInicio = claseInicio;
		this.claseFecha = claseFecha;
		this.claseTitulo = claseTitulo;
		this.ofertaId = ofertaId;
		this.ofertaNombre = ofertaNombre;
		this.presentes = presentes;
		this.honorarioId = honorarioId;
		this.importePorParticipante = importePorParticipante;
		this.importeClase = importeClase;
		this.instructores = instructores;
		this.posicion = posicion;
		this.importeColaborador = importeColaborador;
		this.moneda = moneda;
	}

	public Long getId() {
		return id;
	}

	public Long getLiquidacionId() {
		return liquidacionId;
	}

	public Long getClaseId() {
		return claseId;
	}

	public Instant getClaseInicio() {
		return claseInicio;
	}

	public LocalDate getClaseFecha() {
		return claseFecha;
	}

	public String getClaseTitulo() {
		return claseTitulo;
	}

	public Long getOfertaId() {
		return ofertaId;
	}

	public String getOfertaNombre() {
		return ofertaNombre;
	}

	public int getPresentes() {
		return presentes;
	}

	public Long getHonorarioId() {
		return honorarioId;
	}

	public BigDecimal getImportePorParticipante() {
		return importePorParticipante;
	}

	public BigDecimal getImporteClase() {
		return importeClase;
	}

	public int getInstructores() {
		return instructores;
	}

	public int getPosicion() {
		return posicion;
	}

	public BigDecimal getImporteColaborador() {
		return importeColaborador;
	}

	public String getMoneda() {
		return moneda;
	}

	public long getLiberadaKey() {
		return liberadaKey;
	}
}
