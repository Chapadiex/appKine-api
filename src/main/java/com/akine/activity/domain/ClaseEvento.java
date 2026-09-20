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
 * Una transicion de una clase. <b>Append-only</b> (RN-M28-009).
 *
 * <p>Sin {@code version}, sin {@code updatedAt}, sin baja logica y con todas las columnas
 * {@code updatable = false}. Su puerto tampoco declara {@code update} ni {@code delete}: la
 * ausencia de esas operaciones es la unica garantia real de que el historial sea inmutable. Misma
 * decision que {@code turno_evento}, {@code caso_evento} y {@code autorizacion_movimiento}.
 *
 * <p>Guarda el intervalo, los recursos y la capacidad <b>anteriores</b>, que es lo que hace que
 * reprogramar sea trazable sin recrear la clase (CA-M12-012-06, CA-M28-005-06).
 */
@Entity
@Table(name = "clase_evento")
public class ClaseEvento {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "clase_id", nullable = false, updatable = false)
	private Long claseId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 20, updatable = false)
	private TipoEventoClase tipo;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado_anterior", length = 16, updatable = false)
	private EstadoClase estadoAnterior;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado_nuevo", nullable = false, length = 16, updatable = false)
	private EstadoClase estadoNuevo;

	@Column(name = "motivo", length = 300, updatable = false)
	private String motivo;

	@Column(name = "inicio_anterior", updatable = false)
	private Instant inicioAnterior;

	@Column(name = "fin_anterior", updatable = false)
	private Instant finAnterior;

	@Column(name = "inicio_nuevo", updatable = false)
	private Instant inicioNuevo;

	@Column(name = "fin_nuevo", updatable = false)
	private Instant finNuevo;

	@Column(name = "capacidad_anterior", updatable = false)
	private Integer capacidadAnterior;

	@Column(name = "capacidad_nueva", updatable = false)
	private Integer capacidadNueva;

	@Column(name = "actor_cuenta_id", updatable = false)
	private Long actorCuentaId;

	@Column(name = "ocurrido_en", nullable = false, updatable = false)
	private Instant ocurridoEn;

	protected ClaseEvento() {
		// Requerido por JPA.
	}

	private ClaseEvento(
			ClaseProgramada clase,
			TipoEventoClase tipo,
			EstadoClase estadoAnterior,
			String motivo,
			Long actorCuentaId,
			Instant ocurridoEn) {

		this.organizationId = clase.getOrganizationId();
		this.consultorioId = clase.getConsultorioId();
		this.claseId = clase.getId();
		this.tipo = tipo;
		this.estadoAnterior = estadoAnterior;
		this.estadoNuevo = clase.getEstado();
		this.motivo = motivo;
		this.actorCuentaId = actorCuentaId;
		this.ocurridoEn = ocurridoEn;
	}

	/** Creacion o cancelacion: no hay intervalo anterior que contar. */
	public static ClaseEvento de(
			ClaseProgramada clase,
			TipoEventoClase tipo,
			EstadoClase estadoAnterior,
			String motivo,
			Long actorCuentaId,
			Instant ocurridoEn) {

		return new ClaseEvento(clase, tipo, estadoAnterior, motivo, actorCuentaId, ocurridoEn);
	}

	/**
	 * Reprogramacion: guarda de donde venia.
	 *
	 * <p>Se construye con los valores viejos <b>tomados antes</b> de mutar la clase, y por eso son
	 * parametros en vez de leerse de la entidad: para cuando este metodo corre, la entidad ya
	 * cambio.
	 */
	public static ClaseEvento reprogramacion(
			ClaseProgramada clase,
			Instant inicioAnterior,
			Instant finAnterior,
			int capacidadAnterior,
			Long actorCuentaId,
			Instant ocurridoEn) {

		ClaseEvento evento = new ClaseEvento(
				clase, TipoEventoClase.REPROGRAMACION, clase.getEstado(), null,
				actorCuentaId, ocurridoEn);
		evento.inicioAnterior = inicioAnterior;
		evento.finAnterior = finAnterior;
		evento.capacidadAnterior = capacidadAnterior;
		evento.inicioNuevo = clase.getInicio();
		evento.finNuevo = clase.getFin();
		evento.capacidadNueva = clase.getCapacidad();
		return evento;
	}

	public Long getId() {
		return id;
	}

	public Long getClaseId() {
		return claseId;
	}

	public TipoEventoClase getTipo() {
		return tipo;
	}

	public EstadoClase getEstadoAnterior() {
		return estadoAnterior;
	}

	public EstadoClase getEstadoNuevo() {
		return estadoNuevo;
	}

	public String getMotivo() {
		return motivo;
	}

	public Instant getInicioAnterior() {
		return inicioAnterior;
	}

	public Instant getFinAnterior() {
		return finAnterior;
	}

	public Instant getInicioNuevo() {
		return inicioNuevo;
	}

	public Instant getFinNuevo() {
		return finNuevo;
	}

	public Integer getCapacidadAnterior() {
		return capacidadAnterior;
	}

	public Integer getCapacidadNueva() {
		return capacidadNueva;
	}

	public Long getActorCuentaId() {
		return actorCuentaId;
	}

	public Instant getOcurridoEn() {
		return ocurridoEn;
	}
}
