package com.akine.person.spi;

import java.time.Instant;

/**
 * Se anulo la obligacion economica de una sesion, y si esa sesion consumio autorizaciones hay que
 * revisarlas (DP-13, RN-M17-003).
 *
 * <p>Lleva el hecho economico, no una orden: {@code person} decide que consumos quedan alcanzados.
 *
 * @param organizationId tenant de la obligacion
 * @param sesionId       sesion de la que nacio la obligacion
 * @param obligacionId   obligacion anulada
 * @param motivo         motivo declarado al anular, para que quien revise sepa por que
 * @param ocurrioEn      instante UTC de la anulacion
 * @param actorCuentaId  cuenta que anulo
 */
public record ConsumoARevisar(
		long organizationId,
		long sesionId,
		long obligacionId,
		String motivo,
		Instant ocurrioEn,
		Long actorCuentaId) {
}
