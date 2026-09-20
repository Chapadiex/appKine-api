package com.akine.billing.application;

import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.TipoMovimiento;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Lo que hay que decir para cargar un ingreso o un egreso a mano
 * (RF-M20-002, RF-M20-003).
 *
 * @param tipo           {@code INGRESO} o {@code EGRESO}. Las reversiones no se cargan por aca:
 *                       tienen su propia operacion porque necesitan el movimiento que compensan
 * @param concepto       que fue. <b>Obligatorio</b>: un movimiento manual sin concepto es plata que
 *                       aparece o desaparece del cajon sin explicacion, y es exactamente lo que una
 *                       auditoria busca
 * @param idempotencyKey clave del cliente para que un doble click no cargue dos veces el mismo
 *                       ingreso. {@code null} la desactiva
 */
public record MovimientoManualCommand(
		TipoMovimiento tipo,
		MedioDePago medio,
		BigDecimal importe,
		String concepto,
		String idempotencyKey) {

	/**
	 * Huella del pedido, para distinguir un reintento de un reuso de clave con otro contenido.
	 *
	 * <p>Mismo criterio que {@code CobroCommand}: la clave NO entra en el hash —lo que se compara es
	 * el CONTENIDO— y los campos van separados por {@code |} para que dos pedidos distintos no
	 * puedan producir la misma cadena.
	 *
	 * <p>El importe se normaliza con {@code stripTrailingZeros}: {@code 850} y {@code 850.00} son el
	 * mismo importe, y sin normalizar un reintento que formatea distinto pareceria otro pedido y
	 * daria un 409 que el usuario no puede entender ni arreglar.
	 */
	public String huella(long consultorioId) {
		String canonico = consultorioId + "|" + tipo + "|" + medio + "|"
				+ importe.stripTrailingZeros().toPlainString() + "|" + concepto;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonico.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
