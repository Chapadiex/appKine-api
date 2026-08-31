package com.akine.clinical.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * Un antecedente clinico de una Historia Clinica (RF-M09-002).
 *
 * <h2>Por que es una fila y no una columna de texto de la historia</h2>
 *
 * <p>RN-M09-004 exige que los cambios clinicos sensibles sean trazables. Cuatro columnas
 * {@code TEXT} en {@code historia_clinica} —una por antecedentes medicos, quirurgicos, alergias y
 * medicacion— son el reflejo obvio y no cumplen esa regla: un {@code UPDATE} pisa lo anterior sin
 * dejar rastro de que decia, quien lo escribio ni cuando.
 *
 * <p>Como fila, cada antecedente lleva su autor y su instante, y sobre todo <b>se da de baja en
 * vez de borrarse</b>. Un antecedente que deja de aplicar sigue siendo consultable, que es la
 * regla maestra 10 aplicada al dato clinico mas propenso a ser "corregido" a mano.
 *
 * <h2>La descripcion no se edita</h2>
 *
 * <p>No hay metodo para cambiar {@link #descripcion}, y es deliberado: editar el texto es
 * exactamente el {@code UPDATE} que este diseño evita. Corregir un antecedente es darlo de baja
 * con motivo y registrar el nuevo, y asi las dos versiones quedan.
 */
@Entity
@Table(name = "historia_clinica_antecedente")
public class AntecedenteClinico extends MarcaTemporal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "historia_clinica_id", nullable = false, updatable = false)
	private Long historiaClinicaId;

	@Enumerated(EnumType.STRING)
	@Column(name = "tipo", nullable = false, length = 24, updatable = false)
	private TipoAntecedente tipo;

	@Column(name = "descripcion", nullable = false, length = 1000, updatable = false)
	private String descripcion;

	@Column(name = "registrado_en", nullable = false, updatable = false)
	private Instant registradoEn;

	@Column(name = "registrado_por", nullable = false, updatable = false)
	private Long registradoPor;

	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deactivation_reason", length = 280)
	private String deactivationReason;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	protected AntecedenteClinico() {
		// Requerido por JPA.
	}

	public AntecedenteClinico(
			Long organizationId,
			Long historiaClinicaId,
			TipoAntecedente tipo,
			String descripcion,
			Instant registradoEn,
			Long registradoPor) {

		this.organizationId =
				exigirNoNulo(organizationId, "El antecedente pertenece siempre a una organizacion");
		this.historiaClinicaId =
				exigirNoNulo(historiaClinicaId, "El antecedente pertenece siempre a una historia");
		this.tipo = exigirNoNulo(tipo, "El antecedente declara siempre su tipo");
		this.descripcion = exigirTexto(descripcion);
		this.registradoEn = exigirNoNulo(registradoEn, "El registro deja siempre su instante");
		this.registradoPor = exigirNoNulo(registradoPor, "El registro deja siempre a su autor");
		this.active = true;
	}

	/**
	 * Baja logica del antecedente, con motivo obligatorio.
	 *
	 * <p>El motivo no es una formalidad: un antecedente clinico que desaparece sin explicacion es
	 * literalmente lo que RN-M09-004 quiere impedir. La base tambien lo exige
	 * ({@code ck_hc_antecedente_baja_coherente}), asi que un camino que evite esta clase falla
	 * igual.
	 */
	public void deactivate(Instant occurredAt, String reason) {
		if (reason == null || reason.isBlank()) {
			throw new IllegalArgumentException(
					"La baja de un antecedente clinico exige un motivo declarado");
		}
		this.active = false;
		this.deletedAt = exigirNoNulo(occurredAt, "La baja registra siempre su instante");
		this.deactivationReason = reason.strip();
	}

	/** {@code true} mientras el antecedente siga aplicando. */
	public boolean isVigente() {
		return active && deletedAt == null;
	}

	private static <T> T exigirNoNulo(T valor, String mensaje) {
		if (valor == null) {
			throw new IllegalArgumentException(mensaje);
		}
		return valor;
	}

	private static String exigirTexto(String valor) {
		if (valor == null || valor.isBlank()) {
			throw new IllegalArgumentException("Un antecedente sin descripcion no registra nada");
		}
		return valor.strip();
	}

	public Long getId() {
		return id;
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public Long getHistoriaClinicaId() {
		return historiaClinicaId;
	}

	public TipoAntecedente getTipo() {
		return tipo;
	}

	public String getDescripcion() {
		return descripcion;
	}

	public Instant getRegistradoEn() {
		return registradoEn;
	}

	public Long getRegistradoPor() {
		return registradoPor;
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
