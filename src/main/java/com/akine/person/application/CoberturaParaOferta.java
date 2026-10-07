package com.akine.person.application;

import com.akine.contracting.spi.ArancelVigente;
import com.akine.person.spi.ReferenciaCongelada;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Que cubre la obra social de una persona para una OFERTA en una fecha (B-3, RF-M08-006,
 * RF-M08-007). Solo lectura: no persiste ni consume nada.
 *
 * @param condicionSugerida {@code COBERTURA} si al menos una cobertura aplica; {@code PARTICULAR} si
 *                          no aplica ninguna. Es una SUGERENCIA: elegir particular aunque haya
 *                          cobertura es una decision del operador (RF-M08-007, RF-M13-005) y no
 *                          toca la cobertura del paciente (CA-M08-007-06)
 * @param precioParticular  lo que costaria como particular ese dia (RF-M16-009). {@code null} si la
 *                          oferta no esta tarifada
 */
public record CoberturaParaOferta(
		long personaId,
		long ofertaId,
		LocalDate fecha,
		boolean admiteObraSocial,
		Condicion condicionSugerida,
		BigDecimal precioParticular,
		String moneda,
		List<Aplicable> aplicables,
		List<NoAplicable> noAplicables) {

	public enum Condicion { COBERTURA, PARTICULAR }

	/** Por que una cobertura vigente no aplica a la oferta. */
	public enum Motivo {
		/** La oferta no admite obra social (RF-M08-006 paso 6, CA-M08-006-06): Pilates particular. */
		OFERTA_NO_ADMITE_OBRA_SOCIAL,
		/** La oferta no declara practicas (A-9): no hay contra que resolver un convenio. */
		OFERTA_SIN_PRACTICAS,
		/** Ningun convenio de la sede con ese plan cubre la fecha (RN-M16-005). */
		SIN_CONVENIO_VIGENTE,
		/** Hay convenio, pero ninguna practica de la oferta tiene arancel vigente en el. */
		SIN_ARANCEL_VIGENTE
	}

	/**
	 * Una practica de la oferta y como resolvio bajo esa cobertura.
	 *
	 * @param arancel {@code null} si no resolvio; entonces {@code motivo} dice por que
	 */
	public record Practica(long practicaId, boolean principal, ArancelVigente arancel, Motivo motivo) {
	}

	/**
	 * Una cobertura que aplica a la oferta.
	 *
	 * @param practicaId la primera practica de la oferta que resolvio, en el orden de DP-11: la
	 *                   principal primero. Es la que se devengaria si la sesion cerrara sin
	 *                   tratamientos y la principal resolviera
	 * @param arancel    el arancel de esa practica; {@code arancel.ofertaId()} distinto de null dice
	 *                   que salio el arancel especifico de la oferta (RF-M16-008)
	 * @param practicas  el detalle por practica, para explicar una oferta con varias
	 */
	public record Aplicable(
			long coberturaId,
			boolean principal,
			ReferenciaCongelada referencia,
			long practicaId,
			ArancelVigente arancel,
			boolean credencialVencida,
			LocalDate credencialVigenciaHasta,
			List<Practica> practicas) {
	}

	/** Una cobertura vigente que no aplica a la oferta, con su motivo y el detalle por practica. */
	public record NoAplicable(
			long coberturaId,
			boolean principal,
			ReferenciaCongelada referencia,
			Motivo motivo,
			List<Practica> practicas) {
	}
}
