package com.akine.activity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Un registro o una correccion de asistencia. <b>Append-only</b> (RN-M28-009).
 *
 * <p>Sin {@code version}, sin {@code updatedAt}, sin baja logica y con todas las columnas
 * {@code updatable = false}. Su puerto tampoco declara {@code update} ni {@code delete}: la
 * ausencia de esas operaciones es la unica garantia real de que el historial sea inmutable. Misma
 * decision que {@link ClaseEvento}, {@code turno_evento} y {@code autorizacion_movimiento}.
 *
 * <p><b>Por que esto y no filas versionadas como en 04.02:</b> la entrada clinica de aquella etapa
 * es un <em>documento</em>, y sus versiones sucesivas tienen que poder leerse enteras. Una
 * asistencia es un <em>hecho con un solo valor vigente</em> —estuvo o no estuvo— y su historia es
 * una secuencia de correcciones. Esa es la forma de {@code turno_evento}.
 */
@Entity
@Table(name = "asistencia_evento")
public class AsistenciaEvento {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "clase_id", nullable = false, updatable = false)
	private Long claseId;

	@Column(name = "asistencia_id", nullable = false, updatable = false)
	private Long asistenciaId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 16, updatable = false)
	private TipoEventoAsistencia tipo;

	@Enumerated(EnumType.STRING)
	@Column(name = "resultado_anterior", length = 16, updatable = false)
	private ResultadoAsistencia resultadoAnterior;

	@Enumerated(EnumType.STRING)
	@Column(name = "resultado_nuevo", nullable = false, length = 16, updatable = false)
	private ResultadoAsistencia resultadoNuevo;

	@Column(name = "motivo", length = 300, updatable = false)
	private String motivo;

	@Column(name = "actor_cuenta_id", updatable = false)
	private Long actorCuentaId;

	@Column(name = "ocurrido_en", nullable = false, updatable = false)
	private Instant ocurridoEn;

	protected AsistenciaEvento() {
		// Requerido por JPA.
	}

	private AsistenciaEvento(
			AsistenciaActividad asistencia,
			TipoEventoAsistencia tipo,
			ResultadoAsistencia anterior,
			String motivo,
			Long actorCuentaId,
			Instant ocurridoEn) {

		this.organizationId = asistencia.getOrganizationId();
		this.consultorioId = asistencia.getConsultorioId();
		this.claseId = asistencia.getClaseId();
		this.asistenciaId = asistencia.getId();
		this.tipo = tipo;
		this.resultadoAnterior = anterior;
		this.resultadoNuevo = asistencia.getResultado();
		this.motivo = motivo;
		this.actorCuentaId = actorCuentaId;
		this.ocurridoEn = ocurridoEn;
	}

	/** Primera afirmacion del hecho. Sin resultado anterior y sin motivo: no habia nada que cambiar. */
	public static AsistenciaEvento registro(
			AsistenciaActividad asistencia, Long actorCuentaId, Instant ocurridoEn) {

		return new AsistenciaEvento(
				asistencia, TipoEventoAsistencia.REGISTRO, null, null, actorCuentaId, ocurridoEn);
	}

	/** Correccion de un hecho ya afirmado. El motivo viaja porque es lo que la hace auditable. */
	public static AsistenciaEvento correccion(
			AsistenciaActividad asistencia,
			ResultadoAsistencia anterior,
			String motivo,
			Long actorCuentaId,
			Instant ocurridoEn) {

		return new AsistenciaEvento(
				asistencia, TipoEventoAsistencia.CORRECCION, anterior, motivo, actorCuentaId,
				ocurridoEn);
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

	public Long getClaseId() {
		return claseId;
	}

	public Long getAsistenciaId() {
		return asistenciaId;
	}

	public TipoEventoAsistencia getTipo() {
		return tipo;
	}

	public ResultadoAsistencia getResultadoAnterior() {
		return resultadoAnterior;
	}

	public ResultadoAsistencia getResultadoNuevo() {
		return resultadoNuevo;
	}

	public String getMotivo() {
		return motivo;
	}

	public Long getActorCuentaId() {
		return actorCuentaId;
	}

	public Instant getOcurridoEn() {
		return ocurridoEn;
	}
}
