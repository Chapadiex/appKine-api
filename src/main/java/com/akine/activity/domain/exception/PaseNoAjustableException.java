package com.akine.activity.domain.exception;

/**
 * 409 {@code pase-no-ajustable}: el estado del pase no admite el ajuste o la anulacion pedidos.
 * Lleva {@code motivo}, en texto para una persona —anulado, vencido, saldo final fuera de rango
 * (RN-M29-008: nunca negativo, y nunca por encima de lo que se compro) o, en la anulacion, creditos
 * ya usados—, y {@code causa}, la misma razon como valor estable para un cliente (desde 0.111.0).
 */
public class PaseNoAjustableException extends RuntimeException {

	/** Por que el pase no admite la operacion. Es la extension {@code causa} del problema. */
	public enum Causa {
		/** El estado guardado no la admite: {@code ANULADO} o {@code VENCIDO} (ajuste). */
		ESTADO,
		/** La vigencia ya termino aunque el job de vencimiento todavia no lo escribio (ajuste). */
		FUERA_DE_VIGENCIA,
		/** El saldo final quedaria negativo o por encima de lo comprado (ajuste, RN-M29-008). */
		FUERA_DE_RANGO,
		/** Tiene creditos usados por asistencias (anulacion). */
		CREDITOS_USADOS,
		/**
		 * El abono ya se renovo: la renovacion automatica se configura sobre el periodo nuevo
		 * (AKINE-08.08).
		 */
		YA_RENOVADO
	}

	private final long paseId;
	private final Causa causa;
	private final String motivo;

	public PaseNoAjustableException(long paseId, Causa causa, String motivo) {
		super("El pase " + paseId + " no admite la operacion: " + motivo);
		this.paseId = paseId;
		this.causa = causa;
		this.motivo = motivo;
	}

	public long getPaseId() {
		return paseId;
	}

	public Causa getCausa() {
		return causa;
	}

	public String getMotivo() {
		return motivo;
	}
}
