package com.akine.scheduling.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Una ocurrencia que la regla de una serie producia y que el alta NO reservo, con su motivo
 * (U-22, DP-43). Mapea {@code turno_serie_omision} (V120).
 *
 * <p>Hasta U-22 un feriado hacia fallar el alta entera. Ahora esa ocurrencia se salta, y esta fila
 * es lo que permite contarlo despues: al releer la serie, en el reintento idempotente del alta y
 * cuando alguien pregunte por que la serie de diez lunes tiene nueve turnos. <b>Append-only</b>:
 * todas las columnas son {@code updatable = false} y el puerto no expone ni modificacion ni borrado.
 */
@Entity
@Table(name = "turno_serie_omision")
public class OcurrenciaOmitida {

	/** Por que se omitio. Hoy hay un solo motivo; el CHECK de V120 fija el conjunto. */
	public enum Motivo {
		/** La sede cierra ese dia por feriado y ninguna apertura lo reabre. */
		FERIADO
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "serie_id", nullable = false, updatable = false)
	private Long serieId;

	@Column(name = "ocurrencia_inicio", nullable = false, updatable = false)
	private Instant ocurrenciaInicio;

	@Column(name = "fecha", nullable = false, updatable = false)
	private LocalDate fecha;

	@Enumerated(EnumType.STRING)
	@Column(name = "motivo", nullable = false, length = 24, updatable = false)
	private Motivo motivo;

	protected OcurrenciaOmitida() {
		// Requerido por JPA.
	}

	public OcurrenciaOmitida(TurnoSerie serie, Instant ocurrenciaInicio, LocalDate fecha, Motivo motivo) {
		this.organizationId = serie.getOrganizationId();
		this.consultorioId = serie.getConsultorioId();
		this.serieId = serie.getId();
		this.ocurrenciaInicio = ocurrenciaInicio;
		this.fecha = fecha;
		this.motivo = motivo;
	}

	public Long getId() {
		return id;
	}

	public Long getSerieId() {
		return serieId;
	}

	public Instant getOcurrenciaInicio() {
		return ocurrenciaInicio;
	}

	public LocalDate getFecha() {
		return fecha;
	}

	public Motivo getMotivo() {
		return motivo;
	}
}
