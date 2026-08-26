package com.akine.resource.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
 * Bloque recurrente de atencion de un profesional en una sede (M05, RF-M05-003). Mapea
 * {@code profesional_disponibilidad} (V23).
 *
 * <h2>No es la disponibilidad de un espacio</h2>
 *
 * <p>Este tipo responde "en que franjas atiende un PROFESIONAL" (RN-M05-001: Profesional +
 * Consultorio). Es ortogonal a si un espacio fisico esta en servicio, que resuelven
 * {@code DisponibilidadView} y {@code EspacioAvailabilityResponse} (M04, 02.02). Las dos hacen
 * falta y no se mezclan: ver diseno §11.
 *
 * <h2>Los dos ejes temporales, otra vez el mismo patron que {@code Espacio}</h2>
 *
 * <pre>
 *   VENTANA OPERATIVA   vigenciaDesde / vigenciaHasta
 *                       "desde cuando rige este bloque". vigenciaHasta EXCLUSIVA, null = sin
 *                       fin previsto.
 *
 *   CICLO DE VIDA       active / deletedAt / deactivationReason
 *                       "este bloque nunca debio existir" (se cargo por error). Irreversible,
 *                       con motivo. RN-M05-003: desvincular al profesional NO borra esta fila
 *                       ni su autoria; el calculo la ignora porque la membership dejo de estar
 *                       vigente, no porque el bloque se haya dado de baja.
 * </pre>
 *
 * <h2>Por que {@code horaDesde}/{@code horaHasta} usan un converter</h2>
 *
 * <p>{@code hora_hasta} admite {@code '24:00:00'} en la base (V23) y {@link LocalTime} no puede
 * representarlo. {@link HoraLocalConverter} traduce en las dos direcciones contra
 * {@link IntervaloLocal#FIN_DE_DIA}.
 */
@Entity
@Table(name = "profesional_disponibilidad")
public class BloqueDisponibilidad extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	/**
	 * Colaborador cuyo bloque describe esta fila. {@code updatable = false}: reasignar el
	 * bloque a otro profesional no es una edicion, es un bloque nuevo (RN-M05-001). Sobrevive a
	 * la desvinculacion (RN-M05-003): esta columna nunca se toca por ese motivo.
	 */
	@Column(name = "membership_id", nullable = false, updatable = false)
	private Long membershipId;

	/** ISO-8601: lunes = 1 .. domingo = 7. */
	@Column(name = "dia_semana", nullable = false)
	private int diaSemana;

	@Convert(converter = HoraLocalConverter.class)
	@Column(name = "hora_desde", nullable = false)
	private LocalTime horaDesde;

	/** EXCLUSIVA. Admite {@link IntervaloLocal#FIN_DE_DIA}; nunca cruza medianoche (V23). */
	@Convert(converter = HoraLocalConverter.class)
	@Column(name = "hora_hasta", nullable = false)
	private LocalTime horaHasta;

	@Column(name = "vigencia_desde", nullable = false)
	private LocalDate vigenciaDesde;

	/** EXCLUSIVA. {@code null} = sin fin previsto. */
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

	protected BloqueDisponibilidad() {
		// Requerido por JPA.
	}

	public BloqueDisponibilidad(
			Long organizationId,
			Long consultorioId,
			Long membershipId,
			int diaSemana,
			LocalTime horaDesde,
			LocalTime horaHasta,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta) {

		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.membershipId = membershipId;
		this.diaSemana = exigirDiaSemanaValido(diaSemana);
		this.horaDesde = horaDesde;
		this.horaHasta = horaHasta;
		this.vigenciaDesde = exigirNoNulo(vigenciaDesde, "El inicio de vigencia es obligatorio");
		this.vigenciaHasta = vigenciaHasta;
		this.active = true;
		exigirHorarioCoherente(horaDesde, horaHasta);
		exigirVigenciaCoherente(this.vigenciaDesde, this.vigenciaHasta);
	}

	/**
	 * Aplica una edicion parcial: cada valor {@code null} deja el campo como estaba. Misma
	 * semantica de PATCH que {@code Espacio#updateDatos}. La deteccion de conflictos contra
	 * otros bloques y contra turnos (RF-M05-005) es responsabilidad de la capa de aplicacion,
	 * que es quien tiene la transaccion y el lock de {@link CalendarioSede}.
	 */
	public void updateDatos(
			Integer diaSemana,
			LocalTime horaDesde,
			LocalTime horaHasta,
			LocalDate vigenciaDesde,
			LocalDate vigenciaHasta,
			boolean limpiarVigenciaHasta) {

		if (diaSemana != null) {
			this.diaSemana = exigirDiaSemanaValido(diaSemana);
		}
		if (horaDesde != null) {
			this.horaDesde = horaDesde;
		}
		if (horaHasta != null) {
			this.horaHasta = horaHasta;
		}
		if (vigenciaDesde != null) {
			this.vigenciaDesde = vigenciaDesde;
		}
		// Dos intenciones distintas que un solo parametro nulable no puede expresar:
		// "no toques el fin de vigencia" y "sacale el fin, que quede sin fin previsto".
		if (limpiarVigenciaHasta) {
			this.vigenciaHasta = null;
		} else if (vigenciaHasta != null) {
			this.vigenciaHasta = vigenciaHasta;
		}
		exigirHorarioCoherente(this.horaDesde, this.horaHasta);
		exigirVigenciaCoherente(this.vigenciaDesde, this.vigenciaHasta);
	}

	/**
	 * Baja logica con motivo declarado. No borra nada: el bloque deja de computar en la
	 * disponibilidad efectiva y conserva intacta su historia.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de un bloque de disponibilidad exige un motivo declarado: sin el, "
							+ "la auditoria no responde por que seis meses despues");
		}
		this.active = false;
		this.deletedAt = occurredAt;
		this.deactivationReason = reason.strip();
	}

	/** {@code true} cuando el bloque admite computar en la disponibilidad efectiva. */
	public boolean isOperable() {
		return active && deletedAt == null;
	}

	/**
	 * {@code true} si la ventana operativa del bloque cubre {@code fecha}. Vigencia superior
	 * EXCLUSIVA, mismo criterio que {@code Espacio#estaEnServicio}.
	 */
	public boolean vigenteEn(LocalDate fecha) {
		if (!isOperable()) {
			return false;
		}
		if (fecha.isBefore(vigenciaDesde)) {
			return false;
		}
		return vigenciaHasta == null || fecha.isBefore(vigenciaHasta);
	}

	/** El intervalo de horas locales que cubre este bloque, para la aritmetica del calculo. */
	public IntervaloLocal intervalo() {
		return new IntervaloLocal(horaDesde, horaHasta);
	}

	private static int exigirDiaSemanaValido(int diaSemana) {
		if (diaSemana < 1 || diaSemana > 7) {
			throw new IllegalArgumentException(
					"El dia de la semana debe estar entre 1 (lunes) y 7 (domingo): " + diaSemana);
		}
		return diaSemana;
	}

	private static void exigirHorarioCoherente(LocalTime horaDesde, LocalTime horaHasta) {
		if (horaDesde == null || horaHasta == null) {
			throw new IllegalArgumentException("El horario de un bloque de disponibilidad es obligatorio");
		}
		if (!horaHasta.isAfter(horaDesde)) {
			throw new IllegalArgumentException(
					"El fin de un bloque de disponibilidad debe ser posterior a su inicio: "
							+ horaDesde + " -> " + horaHasta);
		}
	}

	private static void exigirVigenciaCoherente(LocalDate vigenciaDesde, LocalDate vigenciaHasta) {
		if (vigenciaDesde == null) {
			throw new IllegalArgumentException("La vigencia de un bloque de disponibilidad exige un inicio explicito");
		}
		if (vigenciaHasta != null && !vigenciaHasta.isAfter(vigenciaDesde)) {
			throw new IllegalArgumentException("El fin de vigencia debe ser posterior a su inicio");
		}
	}

	private static LocalDate exigirNoNulo(LocalDate valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
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

	public int getDiaSemana() {
		return diaSemana;
	}

	public LocalTime getHoraDesde() {
		return horaDesde;
	}

	public LocalTime getHoraHasta() {
		return horaHasta;
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
