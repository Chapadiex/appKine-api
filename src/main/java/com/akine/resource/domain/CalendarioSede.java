package com.akine.resource.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Politica de calendario de una sede (M05): si cierra automaticamente los feriados del
 * calendario nacional ({@link Feriado}). Mapea {@code consultorio_calendario} (V23).
 *
 * <h2>Por que es una tabla propia de {@code resource} y no una columna en {@code consultorio}</h2>
 *
 * <p>Lo natural seria {@code consultorio.cierra_por_feriado}, pero esa tabla es del modulo
 * {@code organization} y este modulo es {@code resource}: escribirla rompe la propiedad de
 * tablas que ArchUnit sostiene. Una tabla propia cuesta una fila por sede.
 *
 * <p>Cumple ademas un segundo rol, y por eso se crea a demanda y nunca se borra: es la fila
 * sobre la que la capa de aplicacion toma el {@code FOR UPDATE} que serializa los writes de
 * disponibilidad de la sede (ver la cabecera de V23). Por eso {@link #getVersion()} existe:
 * el bloqueo optimista evita que una edicion de politica pise en silencio a otra concurrente,
 * aunque el bloqueo pesimista de escritura de disponibilidad sea harina de otro costal.
 *
 * <p>No tiene baja logica: una sede sin fila propia simplemente no fue consultada ni editada
 * todavia, y eso no es un estado que haya que auditar.
 */
@Entity
@Table(name = "consultorio_calendario")
public class CalendarioSede extends MarcaTemporal {

	/** ISO 3166-1 alfa-2 por defecto de la politica, igual que en {@link Feriado}. */
	public static final String PAIS_POR_DEFECTO = Feriado.PAIS_POR_DEFECTO;

	/** Valor por defecto al crear la fila a demanda (V23): la sede cierra los feriados. */
	public static final boolean CIERRA_POR_FERIADO_POR_DEFECTO = true;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	/**
	 * Sede cuya politica describe esta fila (RN-M05-001). {@code updatable = false}: la
	 * politica no se muda de sede, se crea una fila nueva para la otra.
	 */
	@Column(name = "consultorio_id", nullable = false, updatable = false)
	private Long consultorioId;

	@Column(name = "pais", nullable = false, length = 2)
	private String pais;

	@Column(name = "cierra_por_feriado", nullable = false)
	private boolean cierraPorFeriado;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected CalendarioSede() {
		// Requerido por JPA.
	}

	/**
	 * Alta a demanda con los valores por defecto (V23 y diseno §2.2): primera vez que la sede
	 * se consulta o se edita.
	 */
	public CalendarioSede(Long organizationId, Long consultorioId) {
		this(organizationId, consultorioId, PAIS_POR_DEFECTO, CIERRA_POR_FERIADO_POR_DEFECTO);
	}

	public CalendarioSede(Long organizationId, Long consultorioId, String pais, boolean cierraPorFeriado) {
		this.organizationId = organizationId;
		this.consultorioId = consultorioId;
		this.pais = exigirTexto(pais, "El pais de la politica de calendario es obligatorio");
		this.cierraPorFeriado = cierraPorFeriado;
	}

	/**
	 * Aplica una edicion parcial: cada valor {@code null} deja el campo como estaba. Misma
	 * semantica de PATCH que {@code Espacio#updateDatos}.
	 */
	public void actualizarPolitica(String pais, Boolean cierraPorFeriado) {
		if (pais != null) {
			this.pais = exigirTexto(pais, "El pais de la politica de calendario es obligatorio");
		}
		if (cierraPorFeriado != null) {
			this.cierraPorFeriado = cierraPorFeriado;
		}
	}

	private static String exigirTexto(String valor, String mensaje) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor.strip();
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

	public String getPais() {
		return pais;
	}

	public boolean isCierraPorFeriado() {
		return cierraPorFeriado;
	}

	public long getVersion() {
		return version;
	}
}
