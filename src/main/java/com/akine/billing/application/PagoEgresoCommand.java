package com.akine.billing.application;

import com.akine.billing.domain.MedioDePago;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Lo que hay que decir para pagar, total o parcialmente, un egreso confirmado (RF-M22-002).
 *
 * <p><b>Un solo medio.</b> Pagarle a un profesional mitad en efectivo y mitad por transferencia son
 * dos hechos distintos, con dos comprobantes y probablemente dos dias: se registran como dos pagos.
 *
 * @param referencia     numero de transferencia o de recibo. Es lo que hace conciliable un pago que
 *                       no es en efectivo
 * @param idempotencyKey <b>importa mas que en ninguna otra operacion de esta etapa</b>: sin ella,
 *                       un doble click saca la plata del cajon dos veces
 */
public record PagoEgresoCommand(
		BigDecimal importe,
		MedioDePago medio,
		String referencia,
		String idempotencyKey) {

	/** Ver {@code EgresoCommand.huella}: la clave no entra en el hash, el contenido si. */
	public String huella(long egresoId) {
		String canonico = egresoId + "|" + medio + "|"
				+ importe.stripTrailingZeros().toPlainString() + "|" + referencia;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonico.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
