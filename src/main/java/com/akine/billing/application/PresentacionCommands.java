package com.akine.billing.application;

import com.akine.billing.domain.MedioDePago;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * Lo que hay que decir para operar sobre un lote.
 *
 * <p>Agrupados en una clase porque son cuatro records chicos del mismo caso de uso: tenerlos
 * sueltos multiplicaria archivos sin agregar una sola decision. Mismo criterio que
 * {@code ArancelCommands} en {@code contracting}.
 */
public final class PresentacionCommands {

	private PresentacionCommands() {
	}

	/**
	 * Alta del borrador (RF-M21-002).
	 *
	 * @param obligacionIds las prestaciones con las que arranca. Puede venir vacio: armar el lote a
	 *                      mano, una por una, es un camino legitimo
	 */
	public record Alta(
			long financiadorId,
			LocalDate periodoDesde,
			LocalDate periodoHasta,
			String moneda,
			java.util.List<Long> obligacionIds) {
	}

	/**
	 * Registro del comprobante externo (RF-M21-005).
	 *
	 * <p>El sistema no lo genera ni lo numera: lo registra. Se emite fuera de AKINE.
	 */
	public record Factura(String numero, LocalDate fecha) {
	}

	/** Debito informado por el financiador (RF-M21-006). {@code motivo} es obligatorio. */
	public record Debito(BigDecimal importe, String motivo) {
	}

	/**
	 * Pago recibido del financiador (RF-M21-007).
	 *
	 * @param fechaPago      cuando pago, que puede no ser cuando se carga. Explicito y nunca un
	 *                       reloj implicito, misma regla que {@code PermissionQuery.at}
	 * @param referencia     numero de transferencia o de recibo del financiador
	 * @param idempotencyKey clave del cliente para que un doble click no cobre dos veces la misma
	 *                       transferencia. {@code null} la desactiva
	 */
	public record Pago(
			BigDecimal importe,
			MedioDePago medio,
			LocalDate fechaPago,
			String referencia,
			String idempotencyKey) {

		/**
		 * Huella del pedido, para distinguir un reintento de un reuso de clave con otro contenido.
		 *
		 * <p>Mismo criterio que {@code CobroCommand} y {@code MovimientoManualCommand}: la clave NO
		 * entra en el hash —lo que se compara es el CONTENIDO— y los campos van separados por
		 * {@code |} para que dos pedidos distintos no puedan producir la misma cadena.
		 *
		 * <p>El importe se normaliza con {@code stripTrailingZeros}: {@code 850} y {@code 850.00}
		 * son el mismo importe, y sin normalizar un reintento que formatea distinto pareceria otro
		 * pedido y daria un 409 que el usuario no puede entender ni arreglar.
		 */
		public String huella(long presentacionId) {
			String canonico = presentacionId + "|" + medio + "|"
					+ importe.stripTrailingZeros().toPlainString() + "|" + fechaPago + "|"
					+ referencia;
			try {
				byte[] digest = MessageDigest.getInstance("SHA-256")
						.digest(canonico.getBytes(StandardCharsets.UTF_8));
				return HexFormat.of().formatHex(digest);
			} catch (NoSuchAlgorithmException imposible) {
				throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
			}
		}
	}
}
