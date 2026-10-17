package com.akine.activity.application;

import java.math.BigDecimal;

/**
 * Que le hizo una asistencia a la deuda de la persona en una oferta {@code POR_CLASE} (U-39,
 * RF-M18-008).
 *
 * @param obligacionId la deuda afectada; {@code null} si no hay
 * @param importe      el importe congelado de esa deuda
 * @param saldo        lo que falta pagar despues de este registro
 * @param estado       el estado de la deuda despues de este registro
 */
public record DevengoDeClase(
		Efecto efecto, Long obligacionId, BigDecimal importe, BigDecimal saldo, String moneda,
		String estado) {

	public enum Efecto {
		/** La persona estuvo y nacio la deuda de la clase. */
		DEVENGADA,
		/** La persona estuvo y la asistencia ya tenia su deuda viva: no se escribio nada. */
		YA_DEVENGADA,
		/** Una correccion a ausente anulo la deuda (no la borra). */
		ANULADA,
		/**
		 * Una correccion a ausente o la cancelacion de la clase encontro la deuda con cobros: no se
		 * anula, porque se cobro. Queda viva y auditada; se regulariza reintegrando el cobro
		 * (RF-M19-010) y anulando la deuda. La asistencia se corrige igual (CA-M19-009-06).
		 */
		CON_COBROS,
		/** Una correccion a ausente no encontro deuda viva que anular. */
		SIN_DEUDA,
		/**
		 * La persona estuvo pero la oferta no tiene precio vigente el dia de la clase: no se devenga.
		 * La asistencia queda registrada igual y auditada para regularizar.
		 */
		SIN_PRECIO,
		/** La correccion no cambia si la persona estuvo (presente y presente con demora). */
		SIN_CAMBIO,
		/** La oferta no cobra por clase, o el resultado es ausente sin haber estado antes. */
		NO_APLICA
	}

	public static DevengoDeClase noAplica() {
		return of(Efecto.NO_APLICA);
	}

	static DevengoDeClase of(Efecto efecto) {
		return new DevengoDeClase(efecto, null, null, null, null, null);
	}
}
