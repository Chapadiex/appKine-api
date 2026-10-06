package com.akine.encounter.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Foto inmutable de un tratamiento realizado en una version de la sesion (C-6, {@code V71}).
 *
 * <p>Copia las columnas de {@link TratamientoRealizado} que describen <b>que se hizo</b>. No
 * copia la baja ni la autoria del registro: la foto solo contiene vigentes, y quien escribio la
 * version ya esta en {@link SesionVersion#getRegistradaPor()}.
 *
 * <p>Todas las columnas son {@code updatable = false} y no hay {@code @Version}: una foto es un
 * hecho pasado.
 */
@Entity
@Table(name = "sesion_version_tratamiento")
public class SesionVersionTratamiento {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "tratamiento_realizado_id", nullable = false, updatable = false)
	private Long tratamientoRealizadoId;

	@Column(name = "orden", nullable = false, updatable = false)
	private int orden;

	@Column(name = "practica_id", nullable = false, updatable = false)
	private Long practicaId;

	@Column(name = "practica_codigo", nullable = false, length = 64, updatable = false)
	private String practicaCodigo;

	@Column(name = "practica_nombre", nullable = false, length = 160, updatable = false)
	private String practicaNombre;

	@Column(name = "tecnica", length = 160, updatable = false)
	private String tecnica;

	@Column(name = "zona", length = 120, updatable = false)
	private String zona;

	@Enumerated(EnumType.STRING)
	@Column(name = "lateralidad", length = 16, updatable = false)
	private Lateralidad lateralidad;

	@Column(name = "duracion_minutos", updatable = false)
	private Integer duracionMinutos;

	@Column(name = "profesional_membership_id", nullable = false, updatable = false)
	private Long profesionalMembershipId;

	@Column(name = "espacio_id", updatable = false)
	private Long espacioId;

	@Column(name = "espacio_nombre", length = 160, updatable = false)
	private String espacioNombre;

	@Column(name = "observacion", length = 500, updatable = false)
	private String observacion;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@OneToMany(cascade = CascadeType.PERSIST, fetch = FetchType.LAZY)
	@JoinColumn(name = "sesion_version_tratamiento_id", nullable = false, updatable = false)
	@OrderBy("orden ASC, id ASC")
	@BatchSize(size = 64)
	private List<SesionVersionTratamientoParametro> parametros = new ArrayList<>();

	protected SesionVersionTratamiento() {
		// Requerido por JPA.
	}

	SesionVersionTratamiento(FotoClinica.Tratamiento vivo, Instant createdAt) {
		TratamientoRealizado tratamiento = vivo.tratamiento();
		this.organizationId = tratamiento.getOrganizationId();
		this.tratamientoRealizadoId = tratamiento.getId();
		this.orden = tratamiento.getOrden();
		this.practicaId = tratamiento.getPracticaId();
		this.practicaCodigo = tratamiento.getPracticaCodigo();
		this.practicaNombre = tratamiento.getPracticaNombre();
		this.tecnica = tratamiento.getTecnica();
		this.zona = tratamiento.getZona();
		this.lateralidad = tratamiento.getLateralidad();
		this.duracionMinutos = tratamiento.getDuracionMinutos();
		this.profesionalMembershipId = tratamiento.getProfesionalMembershipId();
		this.espacioId = tratamiento.getEspacioId();
		this.espacioNombre = tratamiento.getEspacioNombre();
		this.observacion = tratamiento.getObservacion();
		this.createdAt = createdAt;
		vivo.parametros().forEach(parametro ->
				parametros.add(new SesionVersionTratamientoParametro(parametro, createdAt)));
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getTratamientoRealizadoId() {
		return tratamientoRealizadoId;
	}

	public int getOrden() {
		return orden;
	}

	public Long getPracticaId() {
		return practicaId;
	}

	public String getPracticaCodigo() {
		return practicaCodigo;
	}

	public String getPracticaNombre() {
		return practicaNombre;
	}

	public String getTecnica() {
		return tecnica;
	}

	public String getZona() {
		return zona;
	}

	public Lateralidad getLateralidad() {
		return lateralidad;
	}

	public Integer getDuracionMinutos() {
		return duracionMinutos;
	}

	public Long getProfesionalMembershipId() {
		return profesionalMembershipId;
	}

	public Long getEspacioId() {
		return espacioId;
	}

	public String getEspacioNombre() {
		return espacioNombre;
	}

	public String getObservacion() {
		return observacion;
	}

	public List<SesionVersionTratamientoParametro> getParametros() {
		return List.copyOf(parametros);
	}
}
