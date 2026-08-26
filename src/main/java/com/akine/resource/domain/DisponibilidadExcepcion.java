package com.akine.resource.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Cierre o apertura puntual de disponibilidad (M05, RF-M05-004). Mapea
 * {@code disponibilidad_excepcion} (V23).
 *
 * <h2>{@code membershipId} nulo es alcance, no un hueco</h2>
 *
 * <p>{@code null} significa excepcion de la SEDE ENTERA: mismo significado que
 * {@code consultorio_id} nulo ya tiene en {@code membership} (V10) y en
 * {@code colaborador_invitacion} (V21). Por eso se mapea como {@link Long} y nunca como
 * {@code long}: una primitiva no puede representar "sin acotar a un profesional puntual" sin
 * inventar un centinela.
 *
 * <h2>{@code horaDesde}/{@code horaHasta} nulos es dia completo</h2>
 *
 * <p>Las dos vienen juntas o ninguna (V23, {@code ck_disponibilidad_excepcion_horario}). Si
 * faltan, la excepcion tapa —o abre— el dia entero; si vienen, recortan una franja con las
 * mismas reglas que {@link BloqueDisponibilidad} (exclusiva, admite
 * {@link IntervaloLocal#FIN_DE_DIA}, nunca cruza medianoche). {@link #intervalo()} devuelve
 * {@link Optional#empty()} exactamente en el caso "dia completo".
 *
 * <p>RN-M05-002: las excepciones prevalecen sobre el horario base. RN-M05-004: los turnos
 * futuros afectados por una excepcion quedan visibles para resolucion (fuera del alcance de
 * esta entidad; lo resuelve el {@code DisponibilidadImpactProbe} de la capa de aplicacion).
 */
@Entity
@Table(name = "disponibilidad_excepcion")
public class DisponibilidadExcepcion extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	/** {@code null} = excepcion de la SEDE ENTERA. Ver el javadoc de la clase. */
	@Column(name = "membership_id", updatable = false)
	private Long membershipId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 20, updatable = false)
	private TipoExcepcion tipo;

	@Enumerated(EnumType.STRING)
	@Column(name = "motivo", nullable = false, length = 20)
	private MotivoExcepcion motivo;

	@Column(name = "fecha_desde", nullable = false)
	private LocalDate fechaDesde;

	/** EXCLUSIVA. */
	@Column(name = "fecha_hasta", nullable = false)
	private LocalDate fechaHasta;

	/** {@code null} junto con {@link #horaHasta} significa DIA COMPLETO. */
	@Convert(converter = HoraLocalConverter.class)
	@Column(name = "hora_desde")
	private LocalTime horaDesde;

	/** EXCLUSIVA. Admite {@link IntervaloLocal#FIN_DE_DIA}. */
	@Convert(converter = HoraLocalConverter.class)
	@Column(name = "hora_hasta")
	private LocalTime horaHasta;

	/** {@link Feriado} que motivo esta excepcion, cuando corresponde. {@code null} si no. */
	@Column(name = "feriado_id")
	private Long feriadoId;

	@Column(name = "notes", length = 280)
	private String notes;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected DisponibilidadExcepcion() {
		// Requerido por JPA.
	}

	public DisponibilidadExcepcion(
			Long organizationId,
			Long consultorioId,
			Long membershipId,
			TipoExcepcion tipo,
			MotivoExcepcion motivo,
			LocalDate fechaDesde,
			LocalDate fechaHasta,
			LocalTime horaDesde,
			LocalTime horaHasta,
			Long feriadoId,
			String notes) {

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.membershipId = membershipId;
		this.tipo = exigirNoNulo(tipo, "El tipo de la excepcion es obligatorio");
		this.motivo = exigirNoNulo(motivo, "El motivo de la excepcion es obligatorio");
		this.fechaDesde = exigirNoNulo(fechaDesde, "El inicio de la excepcion es obligatorio");
		this.fechaHasta = exigirNoNulo(fechaHasta, "El fin de la excepcion es obligatorio");
		this.horaDesde = horaDesde;
		this.horaHasta = horaHasta;
		this.feriadoId = feriadoId;
		this.notes = vacioEsNulo(notes);
		this.active = true;
		exigirFechasCoherentes(this.fechaDesde, this.fechaHasta);
		exigirHorarioCoherente(horaDesde, horaHasta);
	}

	/**
	 * Aplica una edicion parcial: cada valor {@code null} deja el campo como estaba. Ni
	 * {@code tipo} ni el alcance ({@code membershipId}) estan aca: los dos son
	 * {@code updatable = false} por lo que dice su columna. Cambiar de que se trata una
	 * excepcion no es editarla, es cargar otra.
	 */
	public void updateDatos(
			MotivoExcepcion motivo,
			LocalDate fechaDesde,
			LocalDate fechaHasta,
			LocalTime horaDesde,
			LocalTime horaHasta,
			boolean limpiarHorario,
			String notes) {

		if (motivo != null) {
			this.motivo = motivo;
		}
		if (fechaDesde != null) {
			this.fechaDesde = fechaDesde;
		}
		if (fechaHasta != null) {
			this.fechaHasta = fechaHasta;
		}
		// Tres intenciones y no dos: "no toques el horario", "sacale el horario, que tape el
		// dia completo" y "poné este horario". limpiarHorario decide entre las dos primeras.
		if (limpiarHorario) {
			this.horaDesde = null;
			this.horaHasta = null;
		} else {
			if (horaDesde != null) {
				this.horaDesde = horaDesde;
			}
			if (horaHasta != null) {
				this.horaHasta = horaHasta;
			}
		}
		if (notes != null) {
			this.notes = vacioEsNulo(notes);
		}
		exigirFechasCoherentes(this.fechaDesde, this.fechaHasta);
		exigirHorarioCoherente(this.horaDesde, this.horaHasta);
	}

	/**
	 * Baja logica con motivo declarado. No borra nada: la excepcion deja de computar en la
	 * disponibilidad efectiva y conserva intacta su historia.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de una excepcion de disponibilidad exige un motivo declarado: sin "
							+ "el, la auditoria no responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	/** {@code true} cuando la excepcion admite computar en la disponibilidad efectiva. */
	public boolean isOperable() {
		return active && deletedAt == null;
	}

	/** {@code true} si es de alcance SEDE ENTERA (membership nulo), no de un profesional puntual. */
	public boolean esDeSede() {
		return membershipId == null;
	}

	/** {@code true} si {@code fecha} cae dentro de {@code [fechaDesde, fechaHasta)}. */
	public boolean cubreFecha(LocalDate fecha) {
		return !fecha.isBefore(fechaDesde) && fecha.isBefore(fechaHasta);
	}

	/**
	 * El intervalo de horas que recorta o abre esta excepcion, o vacio si tapa el dia completo.
	 */
	public Optional<IntervaloLocal> intervalo() {
		if (horaDesde == null || horaHasta == null) {
			return Optional.empty();
		}
		return Optional.of(new IntervaloLocal(horaDesde, horaHasta));
	}

	private static void exigirFechasCoherentes(LocalDate fechaDesde, LocalDate fechaHasta) {
		if (fechaDesde == null || fechaHasta == null) {
			throw new IllegalArgumentException("El rango de fechas de la excepcion es obligatorio");
		}
		if (!fechaHasta.isAfter(fechaDesde)) {
			throw new IllegalArgumentException(
					"El fin de una excepcion debe ser posterior a su inicio: "
							+ fechaDesde + " -> " + fechaHasta);
		}
	}

	private static void exigirHorarioCoherente(LocalTime horaDesde, LocalTime horaHasta) {
		boolean ningunaHora = horaDesde == null && horaHasta == null;
		boolean ambasHoras = horaDesde != null && horaHasta != null;
		if (!ningunaHora && !ambasHoras) {
			throw new IllegalArgumentException(
					"El horario de una excepcion viene completo o no viene: ambos null significa "
							+ "dia completo");
		}
		if (ambasHoras && !horaHasta.isAfter(horaDesde)) {
			throw new IllegalArgumentException(
					"El fin del horario de una excepcion debe ser posterior a su inicio: "
							+ horaDesde + " -> " + horaHasta);
		}
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	private static String vacioEsNulo(String valor) {
		return valor == null || valor.isBlank() ? null : valor.strip();
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

	public Long getMembershipId() {
		return membershipId;
	}

	public TipoExcepcion getTipo() {
		return tipo;
	}

	public MotivoExcepcion getMotivo() {
		return motivo;
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

	public Long getFeriadoId() {
		return feriadoId;
	}

	public String getNotes() {
		return notes;
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
