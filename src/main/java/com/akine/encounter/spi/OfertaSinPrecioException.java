package com.akine.encounter.spi;

import java.time.LocalDate;

/**
 * La atencion no se puede cerrar porque la deuda del paciente es el precio particular y la oferta
 * no tiene precio vigente ese dia. <b>409 {@code oferta-sin-precio}</b> (AKINE E-7b, DP-17).
 *
 * <p>Vive en {@code spi} y no en {@code encounter.domain} porque la lanzan dos lados: el cierre,
 * que la valida ANTES de numerar, y el devengo de {@code billing} como red si una lectura viva
 * cambio entre la validacion y el devengo (un arancel dado de baja en el medio). En los dos casos
 * la transaccion del cierre entera se revierte y la sesion sigue abierta.
 *
 * <p><b>No es un error del profesional</b>: es un dato administrativo que falta. Por eso el
 * problema dice que oferta y que dia, para que quien tenga permiso cargue el precio y el cierre
 * se reintente tal cual.
 */
public class OfertaSinPrecioException extends RuntimeException {

	/** Por que la deuda del paciente tenia que salir del precio particular. */
	public enum Motivo {
		/** El mostrador resolvio la recepcion del turno como Particular (RF-M08-007, E-7). */
		PARTICULAR_POR_RECEPCION,
		/** La oferta no se le puede facturar a un financiador (M27, F-4). */
		OFERTA_SIN_OBRA_SOCIAL,
		/** El paciente no tiene cobertura con convenio y arancel aplicables a la practica. */
		SIN_COBERTURA_APLICABLE;

		/**
		 * El motivo de un cierre que ya se sabe que cae a particular. Un solo lugar para que el
		 * cierre y el devengo nombren igual la misma causa.
		 */
		public static Motivo de(SesionCerrada cierre) {
			if (cierre.particularPorRecepcion()) {
				return PARTICULAR_POR_RECEPCION;
			}
			return cierre.ofertaAdmiteObraSocial() ? SIN_COBERTURA_APLICABLE : OFERTA_SIN_OBRA_SOCIAL;
		}
	}

	private final long ofertaId;
	private final LocalDate dia;
	private final Motivo motivo;

	public OfertaSinPrecioException(long ofertaId, LocalDate dia, Motivo motivo) {
		super("La oferta " + ofertaId + " no tiene precio vigente el " + dia
				+ " y la atencion se cobra al precio particular (" + motivo + ")");
		this.ofertaId = ofertaId;
		this.dia = dia;
		this.motivo = motivo;
	}

	public long getOfertaId() {
		return ofertaId;
	}

	public LocalDate getDia() {
		return dia;
	}

	public Motivo getMotivo() {
		return motivo;
	}
}
