package com.akine.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * La regla con la que se genero una serie de turnos (AKINE E-3, DP-04, ADR-0011).
 *
 * <p><b>No es duena del ciclo de vida de sus turnos.</b> Cada ocurrencia es un {@link Turno} pleno,
 * con estado, version e historial propios; la serie solo dice como se generaron. Por eso no tiene
 * estado ni baja: "la serie esta cancelada" es un hecho que se lee en sus turnos, no una columna que
 * pueda contradecirlos.
 *
 * <p>Inmutable despues del alta, salvo {@code version}: reprogramar por alcance mueve los turnos y
 * NO reescribe la regla, que sigue describiendo como se genero la serie.
 */
@Entity
@Table(name = "turno_serie")
public class TurnoSerie {

	private static final String SEMANAL = "SEMANAL";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "profesional_membership_id", updatable = false)
	private Long profesionalMembershipId;

	@Column(name = "frecuencia", nullable = false, length = 16, updatable = false)
	private String frecuencia;

	@Column(name = "dias_semana", nullable = false, length = 16, updatable = false)
	private String diasSemana;

	@Column(name = "hora_local", nullable = false, updatable = false)
	private LocalTime horaLocal;

	@Column(name = "fecha_desde", nullable = false, updatable = false)
	private LocalDate fechaDesde;

	@Column(name = "fecha_hasta", updatable = false)
	private LocalDate fechaHasta;

	@Column(name = "cantidad", updatable = false)
	private Integer cantidad;

	@Column(name = "timezone", nullable = false, length = 64, updatable = false)
	private String timezone;

	@Column(name = "cantidad_generada", nullable = false, updatable = false)
	private int cantidadGenerada;

	@Column(name = "idempotency_key", length = 80, updatable = false)
	private String idempotencyKey;

	@Column(name = "request_hash", length = 64, updatable = false)
	private String requestHash;

	@Column(name = "creada_por_cuenta_id", nullable = false, updatable = false)
	private Long creadaPorCuentaId;

	@Column(name = "creada_en", nullable = false, updatable = false)
	private Instant creadaEn;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected TurnoSerie() {
		// Requerido por JPA.
	}

	public TurnoSerie(
			long organizationId,
			long consultorioId,
			long ofertaId,
			long personaId,
			Long profesionalMembershipId,
			ReglaDeRecurrencia regla,
			String timezone,
			int cantidadGenerada,
			long creadaPorCuentaId,
			Instant creadaEn,
			String idempotencyKey,
			String requestHash) {

		if ((idempotencyKey == null) != (requestHash == null)) {
			throw new IllegalArgumentException(
					"La clave de idempotencia y el hash del pedido viajan juntos o no viajan");
		}
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.ofertaId = ofertaId;
		this.personaId = personaId;
		this.profesionalMembershipId = profesionalMembershipId;
		this.frecuencia = SEMANAL;
		this.diasSemana = regla.diasComoTexto();
		this.horaLocal = regla.hora();
		this.fechaDesde = regla.desde();
		this.fechaHasta = regla.hasta();
		this.cantidad = regla.cantidad();
		this.timezone = timezone;
		this.cantidadGenerada = cantidadGenerada;
		this.creadaPorCuentaId = creadaPorCuentaId;
		this.creadaEn = creadaEn;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
	}

	public ReglaDeRecurrencia getRegla() {
		return new ReglaDeRecurrencia(
				ReglaDeRecurrencia.diasDesdeTexto(diasSemana), horaLocal, fechaDesde, fechaHasta, cantidad);
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

	public Long getPersonaId() {
		return personaId;
	}

	public Long getProfesionalMembershipId() {
		return profesionalMembershipId;
	}

	public String getFrecuencia() {
		return frecuencia;
	}

	public String getTimezone() {
		return timezone;
	}

	public int getCantidadGenerada() {
		return cantidadGenerada;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestHash() {
		return requestHash;
	}

	public Long getCreadaPorCuentaId() {
		return creadaPorCuentaId;
	}

	public Instant getCreadaEn() {
		return creadaEn;
	}

	public long getVersion() {
		return version;
	}
}
