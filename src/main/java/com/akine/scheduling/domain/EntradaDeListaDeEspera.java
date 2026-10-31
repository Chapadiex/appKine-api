package com.akine.scheduling.domain;

import com.akine.scheduling.domain.exception.EntradaDeEsperaCerradaException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Un paciente que espera lugar para una prestacion (TURNO.md §20).
 *
 * <p><b>No es un turno sin fecha.</b> Un turno ocupa un lugar; esto describe que lugar le serviria a
 * alguien. Por eso no aparece en la agenda ni descuenta cupo, y por eso asignarla crea un turno
 * comun por el mismo camino que cualquier otro.
 */
@Entity
@Table(name = "lista_espera_turno")
public class EntradaDeListaDeEspera {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "persona_id", nullable = false, updatable = false)
	private Long personaId;

	@Column(name = "oferta_id", nullable = false, updatable = false)
	private Long ofertaId;

	@Column(name = "profesional_membership_id", updatable = false)
	private Long profesionalMembershipId;

	@Column(name = "caso_id", updatable = false)
	private Long casoId;

	@Column(name = "plan_id", updatable = false)
	private Long planId;

	@Column(name = "fecha_desde", nullable = false, updatable = false)
	private LocalDate fechaDesde;

	@Column(name = "fecha_hasta", nullable = false, updatable = false)
	private LocalDate fechaHasta;

	@Column(name = "dias_semana", length = 13, updatable = false)
	private String diasSemana;

	@Column(name = "hora_desde", updatable = false)
	private LocalTime horaDesde;

	@Column(name = "hora_hasta", updatable = false)
	private LocalTime horaHasta;

	@Enumerated(EnumType.STRING)
	@Column(name = "prioridad", nullable = false, length = 8, updatable = false)
	private PrioridadListaDeEspera prioridad;

	@Column(name = "notas", length = 300, updatable = false)
	private String notas;

	@Enumerated(EnumType.STRING)
	@Column(name = "estado", nullable = false, length = 12)
	private EstadoListaDeEspera estado;

	@Column(name = "turno_id")
	private Long turnoId;

	@Column(name = "motivo_cierre", length = 300)
	private String motivoCierre;

	@Column(name = "creada_por_cuenta_id", nullable = false, updatable = false)
	private Long creadaPorCuentaId;

	@Column(name = "creada_en", nullable = false, updatable = false)
	private Instant creadaEn;

	@Column(name = "cerrada_por_cuenta_id")
	private Long cerradaPorCuentaId;

	@Column(name = "cerrada_en")
	private Instant cerradaEn;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected EntradaDeListaDeEspera() {
		// Requerido por JPA.
	}

	public EntradaDeListaDeEspera(
			long organizationId,
			long consultorioId,
			long personaId,
			long ofertaId,
			Long profesionalMembershipId,
			Long casoId,
			Long planId,
			LocalDate fechaDesde,
			LocalDate fechaHasta,
			Set<DayOfWeek> dias,
			LocalTime horaDesde,
			LocalTime horaHasta,
			PrioridadListaDeEspera prioridad,
			String notas,
			long creadaPorCuentaId,
			Instant creadaEn) {

		if (fechaDesde == null || fechaHasta == null || fechaHasta.isBefore(fechaDesde)) {
			throw new IllegalArgumentException("El rango de fechas de la espera no es valido");
		}
		if ((horaDesde == null) != (horaHasta == null)
				|| (horaDesde != null && !horaHasta.isAfter(horaDesde))) {
			throw new IllegalArgumentException("La franja horaria de la espera no es valida");
		}
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.personaId = personaId;
		this.ofertaId = ofertaId;
		this.profesionalMembershipId = profesionalMembershipId;
		this.casoId = casoId;
		this.planId = planId;
		this.fechaDesde = fechaDesde;
		this.fechaHasta = fechaHasta;
		this.diasSemana = dias == null || dias.isEmpty() ? null : dias.stream()
				.sorted()
				.map(dia -> String.valueOf(dia.getValue()))
				.collect(Collectors.joining(","));
		this.horaDesde = horaDesde;
		this.horaHasta = horaHasta;
		this.prioridad = prioridad == null ? PrioridadListaDeEspera.NORMAL : prioridad;
		this.notas = notas == null || notas.isBlank() ? null : notas.strip();
		this.estado = EstadoListaDeEspera.ACTIVA;
		this.creadaPorCuentaId = creadaPorCuentaId;
		this.creadaEn = creadaEn;
	}

	/**
	 * {@code true} si el horario le sirve a esta entrada.
	 *
	 * <p>La franja se compara con el <b>inicio</b> y con el fin: un turno de 45 minutos que arranca a
	 * las 17:30 no le sirve a quien pidio "hasta las 18:00".
	 */
	public boolean admite(long ofertaId, Long profesionalId, LocalDate fecha, LocalTime inicio, LocalTime fin) {
		if (estado != EstadoListaDeEspera.ACTIVA || !this.ofertaId.equals(ofertaId)) {
			return false;
		}
		if (profesionalMembershipId != null && !profesionalMembershipId.equals(profesionalId)) {
			return false;
		}
		if (fecha.isBefore(fechaDesde) || fecha.isAfter(fechaHasta)) {
			return false;
		}
		if (diasSemana != null && !dias().contains(fecha.getDayOfWeek())) {
			return false;
		}
		return horaDesde == null || (!inicio.isBefore(horaDesde) && !fin.isAfter(horaHasta));
	}

	public Set<DayOfWeek> dias() {
		Set<DayOfWeek> resultado = EnumSet.noneOf(DayOfWeek.class);
		if (diasSemana != null) {
			for (String dia : diasSemana.split(",")) {
				resultado.add(DayOfWeek.of(Integer.parseInt(dia)));
			}
		}
		return resultado;
	}

	/** Le asigna el turno que se le reservo. */
	public void asignar(long turnoId, long cuentaId, Instant occurredAt) {
		exigirActiva();
		this.estado = EstadoListaDeEspera.ASIGNADA;
		this.turnoId = turnoId;
		this.cerradaPorCuentaId = cuentaId;
		this.cerradaEn = occurredAt;
	}

	/** La saca de la lista sin asignarle nada: ya consiguio, no contesta, cambio de idea. */
	public void descartar(String motivo, long cuentaId, Instant occurredAt) {
		exigirActiva();
		this.estado = EstadoListaDeEspera.DESCARTADA;
		this.motivoCierre = motivo == null || motivo.isBlank() ? null : motivo.strip();
		this.cerradaPorCuentaId = cuentaId;
		this.cerradaEn = occurredAt;
	}

	public void exigirActiva() {
		if (estado != EstadoListaDeEspera.ACTIVA) {
			throw new EntradaDeEsperaCerradaException(id, estado.name());
		}
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

	public Long getPersonaId() {
		return personaId;
	}

	public Long getOfertaId() {
		return ofertaId;
	}

	public Long getProfesionalMembershipId() {
		return profesionalMembershipId;
	}

	public Long getCasoId() {
		return casoId;
	}

	public Long getPlanId() {
		return planId;
	}

	public LocalDate getFechaDesde() {
		return fechaDesde;
	}

	public LocalDate getFechaHasta() {
		return fechaHasta;
	}

	public LocalTime getHoraDesde() {
		return horaDesde;
	}

	public LocalTime getHoraHasta() {
		return horaHasta;
	}

	public PrioridadListaDeEspera getPrioridad() {
		return prioridad;
	}

	public String getNotas() {
		return notas;
	}

	public EstadoListaDeEspera getEstado() {
		return estado;
	}

	public Long getTurnoId() {
		return turnoId;
	}

	public String getMotivoCierre() {
		return motivoCierre;
	}

	public Instant getCreadaEn() {
		return creadaEn;
	}

	public long getVersion() {
		return version;
	}
}
