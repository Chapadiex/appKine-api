package com.akine.billing.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Lo que costaria una atencion de esa oferta para esa persona ese dia, calculado con la MISMA regla
 * que el devengo del cierre ({@link CotizadorDeAtencion}). Es una lectura viva: no se guarda.
 *
 * @param condicion        {@code COBERTURA} o {@code PARTICULAR}
 * @param motivoParticular {@code null} con cobertura; si no {@code SIN_COBERTURA}, {@code NO_ADMITE_OS},
 *                         {@code TOPE_MENSUAL} o {@code SIN_CONVENIO}
 * @param alcanceConvenio  {@code PLAN} (convenio del plan, todas las sedes) o {@code SEDE}
 * @param aCobrarAlPaciente {@code null} si es particular y la oferta no tiene precio ese dia: el
 *                         cierre se bloquearia con {@code oferta-sin-precio}
 * @param pasos            la explicacion en orden, en castellano llano
 */
public record Cotizacion(
		long ofertaId,
		String ofertaNombre,
		BigDecimal precioParticular,
		String moneda,
		String condicion,
		String motivoParticular,
		Long coberturaId,
		String financiador,
		String plan,
		String convenio,
		String alcanceConvenio,
		BigDecimal importeFinanciador,
		BigDecimal coseguro,
		BigDecimal aCobrarAlPaciente,
		Integer topeMensual,
		Integer sesionesCubiertasEnElMes,
		LocalDate vigenteDesde,
		LocalDate vigenteHasta,
		List<String> pasos) {

	public static final String COBERTURA = "COBERTURA";
	public static final String PARTICULAR = "PARTICULAR";
	public static final String SIN_COBERTURA = "SIN_COBERTURA";
	public static final String NO_ADMITE_OS = "NO_ADMITE_OS";
	public static final String TOPE_MENSUAL = "TOPE_MENSUAL";
	public static final String SIN_CONVENIO = "SIN_CONVENIO";

	public Cotizacion {
		pasos = pasos == null ? List.of() : List.copyOf(pasos);
	}
}
