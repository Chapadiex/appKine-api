package com.akine.billing.application;

import com.akine.billing.domain.FinanciadorPago;
import com.akine.billing.domain.MedioDePago;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un pago del financiador, tal como sale del backend.
 *
 * <p>{@code movimientoCajaId} viaja a proposito: es la prueba de RN-M21-002 —que el pago genero
 * caja— y lo que permite ir del lote al movimiento sin adivinar. Un pago que no lo mostrara seria
 * indistinguible de una anotacion administrativa. <b>No es una columna de la tabla</b>: el vinculo
 * lo guarda el movimiento, y esta vista lo devuelve porque el servicio acaba de crearlo.
 */
public record FinanciadorPagoView(
		long id,
		long presentacionId,
		long financiadorId,
		BigDecimal importe,
		String moneda,
		MedioDePago medio,
		LocalDate fechaPago,
		String referencia,
		Long movimientoCajaId,
		Instant registradoEn) {

	public static FinanciadorPagoView de(FinanciadorPago pago, Long movimientoCajaId) {
		return new FinanciadorPagoView(
				pago.getId(),
				pago.getPresentacionId(),
				pago.getFinanciadorId(),
				pago.getImporte(),
				pago.getMoneda(),
				pago.getMedio(),
				pago.getFechaPago(),
				pago.getReferencia(),
				movimientoCajaId,
				pago.getRegistradoEn());
	}
}
