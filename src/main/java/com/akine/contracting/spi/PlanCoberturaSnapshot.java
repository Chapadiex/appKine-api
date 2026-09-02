package com.akine.contracting.spi;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lo que otro modulo necesita saber de un Plan de cobertura sin depender de su entidad.
 *
 * <p><b>Lectura VIVA.</b> Igual que {@link FinanciadorSnapshot}: sirve para decidir, no para
 * guardar. Lo que se guarda es {@link ReferenciaDeCobertura}.
 *
 * <p>Trae {@link #financiadorOperable} y no solo el {@code financiadorId} porque la pregunta que
 * este record existe para responder —{@link #seleccionableEl(LocalDate)}— no se puede contestar
 * sin el: un plan impecable de un financiador dado de baja no se puede elegir. Obligar al
 * llamador a hacer una segunda consulta para enterarse seria un invitacion a olvidarla.
 *
 * @param vigenciaHasta ultimo dia INCLUSIVE. {@code null} = sin fin previsto
 * @param copago        {@code null} = sin copago declarado, que NO es lo mismo que cero
 */
public record PlanCoberturaSnapshot(
		long id,
		long organizationId,
		long financiadorId,
		String financiadorNombre,
		boolean financiadorOperable,
		String codigo,
		String nombre,
		LocalDate vigenciaDesde,
		LocalDate vigenciaHasta,
		boolean requiereAutorizacion,
		boolean requiereCredencial,
		BigDecimal copago,
		String moneda,
		boolean operable) {

	/**
	 * El plan se puede ELEGIR para una operacion nueva ese dia (RN-M15-002).
	 *
	 * <p>Se evalua <b>dia por dia</b> y nunca contra una ventana entera: un plan que vence el 15
	 * no puede seguir eligiendose el 20 porque la consulta abarco todo el mes. Misma regla que
	 * {@code OfertaSnapshot.vigenteEl}.
	 */
	public boolean seleccionableEl(LocalDate fecha) {
		if (!financiadorOperable || !operable || fecha == null || fecha.isBefore(vigenciaDesde)) {
			return false;
		}
		return vigenciaHasta == null || !fecha.isAfter(vigenciaHasta);
	}
}
