package com.akine.resource.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Una franja del horario general de una sede (RF-M03-002, RN-M03-004). Mapea
 * {@code consultorio_horario} (V83).
 *
 * <p>Es parte de la politica de calendario de la sede —cuando abre, igual que si cierra los
 * feriados ({@link CalendarioSede})— y por eso vive en este modulo y se lee por el mismo recurso.
 * <b>Desde A-8b (DP-19) es un limite, no informacion</b>: la disponibilidad efectiva de cada
 * profesional se intersecta con estas franjas ({@link HorarioDeSede}). No reemplaza la
 * disponibilidad individual —RN-M03-004 sigue en pie: no abre nada que el profesional no tenga—,
 * solo la recorta. Sin franjas vigentes no se limita nada.
 *
 * <p>Misma recurrencia que un bloque de disponibilidad: dia ISO-8601 y franja local
 * {@code [horaDesde, horaHasta)}, con {@link IntervaloLocal#FIN_DE_DIA} admitido y sin cruzar
 * medianoche. Editar el horario no muta estas filas: las da de baja ({@link #reemplazar}) e
 * inserta las nuevas, asi la version anterior sigue consultable (regla maestra 10).
 */
@Entity
@Table(name = "consultorio_horario")
public class FranjaHorarioGeneral extends MarcaTemporal {

	/** Tope defensivo: siete dias con cuatro franjas cada uno sobra para cualquier sede real. */
	public static final int MAXIMO_FRANJAS = 28;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@JdbcTypeCode(SqlTypes.TINYINT)
	@Column(name = "dia_semana", nullable = false, updatable = false)
	private int diaSemana;

	@Convert(converter = HoraLocalConverter.class)
	@JdbcType(HoraJdbcType.class)
	@Column(name = "hora_desde", nullable = false, updatable = false)
	private LocalTime horaDesde;

	@Convert(converter = HoraLocalConverter.class)
	@JdbcType(HoraJdbcType.class)
	@Column(name = "hora_hasta", nullable = false, updatable = false)
	private LocalTime horaHasta;

	@Column(name = "active", nullable = false)
	private boolean active;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected FranjaHorarioGeneral() {
		// Requerido por JPA.
	}

	public FranjaHorarioGeneral(
			Long organizationId, Long consultorioId, Franja franja) {
		this.organizationId = Objects.requireNonNull(organizationId);
		this.consultorioId = Objects.requireNonNull(consultorioId);
		this.diaSemana = franja.diaSemana();
		this.horaDesde = franja.horaDesde();
		this.horaHasta = franja.horaHasta();
		this.active = true;
	}

	/** La franja deja de ser vigente porque una edicion posterior reemplazo el horario. */
	public void reemplazar(Instant ahora) {
		if (!active) {
			return;
		}
		this.active = false;
		this.deletedAt = ahora;
	}

	public Franja franja() {
		return new Franja(diaSemana, horaDesde, horaHasta);
	}

	// =================================================================================
	// Validacion del horario completo
	// =================================================================================

	/**
	 * Valida un horario general entero y lo devuelve ordenado por dia y hora.
	 *
	 * <p>Cada franja ya se valida sola al construirse ({@link Franja}); lo que se agrega aca es
	 * lo que solo se puede ver con todas juntas: que dos franjas del mismo dia no se pisen, y
	 * el tope de cantidad. Dos franjas contiguas (09-13 y 13-17) son validas: no comparten
	 * ningun instante porque el extremo superior es exclusivo.
	 *
	 * @throws IllegalArgumentException si dos franjas del mismo dia se solapan o hay demasiadas
	 */
	public static List<Franja> validarHorario(List<Franja> franjas) {
		if (franjas == null || franjas.isEmpty()) {
			return List.of();
		}
		if (franjas.size() > MAXIMO_FRANJAS) {
			throw new IllegalArgumentException(
					"El horario general admite como maximo " + MAXIMO_FRANJAS + " franjas");
		}
		List<Franja> ordenadas = new ArrayList<>(franjas);
		ordenadas.sort(Comparator.comparingInt(Franja::diaSemana)
				.thenComparing(Franja::horaDesde));
		for (int i = 1; i < ordenadas.size(); i++) {
			Franja anterior = ordenadas.get(i - 1);
			Franja actual = ordenadas.get(i);
			if (anterior.diaSemana() == actual.diaSemana()
					&& anterior.intervalo().solapaCon(actual.intervalo())) {
				throw new IllegalArgumentException(
						"Dos franjas del horario general se solapan el dia " + actual.diaSemana()
								+ ": " + anterior.horaDesde() + "-" + anterior.horaHasta()
								+ " y " + actual.horaDesde() + "-" + actual.horaHasta());
			}
		}
		return List.copyOf(ordenadas);
	}

	/**
	 * Una franja semanal: dia ISO-8601 (1 = lunes) y horas locales {@code [desde, hasta)}.
	 * Medianoche como fin se expresa con {@link IntervaloLocal#FIN_DE_DIA}.
	 */
	public record Franja(int diaSemana, LocalTime horaDesde, LocalTime horaHasta) {

		public Franja {
			if (diaSemana < 1 || diaSemana > 7) {
				throw new IllegalArgumentException(
						"El dia de la semana debe estar entre 1 (lunes) y 7 (domingo): " + diaSemana);
			}
			if (horaDesde == null || horaHasta == null) {
				throw new IllegalArgumentException(
						"Una franja del horario general exige hora de inicio y de fin");
			}
			if (!horaHasta.isAfter(horaDesde)) {
				throw new IllegalArgumentException(
						"El fin de una franja del horario general debe ser posterior a su inicio: "
								+ horaDesde + " -> " + horaHasta);
			}
		}

		IntervaloLocal intervalo() {
			return new IntervaloLocal(horaDesde, horaHasta);
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

	public boolean isActive() {
		return active;
	}

	public Instant getDeletedAt() {
		return deletedAt;
	}
}
