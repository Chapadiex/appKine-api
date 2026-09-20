package com.akine.billing.application;

import java.math.BigDecimal;

/**
 * El estado de cuenta de un financiador con la organizacion.
 *
 * <h2>Esto NO es la caja</h2>
 *
 * <p>La caja es un cajon de una sede, con jornada, arqueo y fecha de negocio: se cuenta. Esto es una
 * relacion comercial que vive en meses y que nadie arquea — nadie cuenta los 400.000 que una obra
 * social debe. Se tocan en un solo punto, el pago, y en ningun otro.
 *
 * <p><b>Cruza sedes a proposito:</b> la relacion es de la organizacion aunque cada lote se arme en
 * una sede, y un centro con dos consultorios negocia una sola cuenta con cada financiador.
 *
 * @param saldo lo reclamado que todavia no quedo explicado: {@code presentado - debitado - cobrado}
 */
public record CuentaCorrienteDeFinanciador(
		long financiadorId,
		String financiadorNombre,
		BigDecimal totalPresentado,
		BigDecimal totalDebitado,
		BigDecimal totalCobrado,
		BigDecimal saldo,
		int lotes) {
}
