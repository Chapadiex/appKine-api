package com.akine.encounter.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;

/**
 * Cuantos dias desde el cierre se puede enmendar una sesion, por organizacion (U-25, DP-43).
 *
 * <p><b>Sin fila rige {@link #VENTANA_POR_DEFECTO}</b>: ninguna organizacion necesito backfill y el
 * valor por defecto vive en un solo lugar. La fila aparece la primera vez que alguien la fija.
 *
 * <p>La ventana se cuenta en <b>instantes</b>, no en dias calendario de la sede: 30 dias desde el
 * cierre son 30 x 24 horas desde {@code cerrada_en}. Es la lectura que no depende de la zona de la
 * sede ni de un cambio de horario, y la que la pantalla puede mostrar como una fecha y hora exacta
 * ({@code enmendableHasta}).
 */
@Entity
@Table(name = "politica_de_enmienda")
public class PoliticaDeEnmienda {

	/** DP-43: 30 dias desde el cierre, si la organizacion no fijo otra cosa. */
	public static final int VENTANA_POR_DEFECTO = 30;
	public static final int VENTANA_MINIMA = 1;
	/** Diez anios, el CHECK de V122: un tope para que un error de tipeo no sea "para siempre". */
	public static final int VENTANA_MAXIMA = 3650;

	@Id
	@Column(name = "organization_id", nullable = false, updatable = false)
	private Long organizationId;

	@Column(name = "ventana_dias", nullable = false)
	private int ventanaDias;

	@Column(name = "actualizada_en", nullable = false)
	private Instant actualizadaEn;

	@Column(name = "actualizada_por_cuenta_id", nullable = false)
	private Long actualizadaPorCuentaId;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	protected PoliticaDeEnmienda() {
		// Requerido por JPA.
	}

	public PoliticaDeEnmienda(long organizationId, int ventanaDias, Instant ahora, long cuentaId) {
		this.organizationId = organizationId;
		fijar(ventanaDias, ahora, cuentaId);
	}

	/** Cambia la ventana. Fuera de rango: {@link IllegalArgumentException} (400 en la API). */
	public void fijar(int nuevaVentana, Instant ahora, long cuentaId) {
		exigirEnRango(nuevaVentana);
		this.ventanaDias = nuevaVentana;
		this.actualizadaEn = ahora;
		this.actualizadaPorCuentaId = cuentaId;
	}

	public static void exigirEnRango(int ventanaDias) {
		if (ventanaDias < VENTANA_MINIMA || ventanaDias > VENTANA_MAXIMA) {
			throw new IllegalArgumentException("La ventana para enmendar va de " + VENTANA_MINIMA
					+ " a " + VENTANA_MAXIMA + " dias");
		}
	}

	/** Hasta cuando se puede enmendar una sesion cerrada en {@code cerradaEn}, con esta ventana. */
	public static Instant enmendableHasta(Instant cerradaEn, int ventanaDias) {
		return cerradaEn.plus(Duration.ofDays(ventanaDias));
	}

	public Long getOrganizationId() {
		return organizationId;
	}

	public int getVentanaDias() {
		return ventanaDias;
	}

	public Instant getActualizadaEn() {
		return actualizadaEn;
	}

	public Long getActualizadaPorCuentaId() {
		return actualizadaPorCuentaId;
	}

	/** {@code null} antes del primer guardado: es lo que le dice a Spring Data que la fila es nueva. */
	public Long getVersion() {
		return version;
	}
}
