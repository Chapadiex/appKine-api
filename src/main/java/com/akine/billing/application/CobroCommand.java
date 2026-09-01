package com.akine.billing.application;

import com.akine.billing.domain.MedioDePago;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Lo que hay que decir para registrar un cobro.
 *
 * @param idempotencyKey clave del cliente para que un reintento no cobre dos veces. {@code null} la
 *                       desactiva, y es legitimo en una carga manual
 */
public record CobroCommand(
		long personaId,
		BigDecimal total,
		List<MedioPedido> medios,
		List<ImputacionPedida> imputaciones,
		String idempotencyKey) {

	public CobroCommand {
		medios = List.copyOf(medios);
		imputaciones = List.copyOf(imputaciones);
	}

	public record MedioPedido(MedioDePago medio, BigDecimal importe, String referencia) {
	}

	public record ImputacionPedida(long obligacionId, BigDecimal importe) {
	}

	/**
	 * Huella del pedido, para distinguir un reintento de un reuso de clave con otro contenido.
	 *
	 * <p>Mismo criterio que {@code scheduling.ReservaCommand}: la clave NO entra en el hash —lo que
	 * se compara es el CONTENIDO— y los campos van separados por {@code |} para que dos pedidos
	 * distintos no puedan producir la misma cadena.
	 *
	 * <p>El total se normaliza con {@code stripTrailingZeros}: {@code 8500} y {@code 8500.00} son el
	 * mismo importe, y sin normalizar un reintento que formatea distinto pareceria otro pedido y
	 * daria un 409 que el usuario no puede entender ni arreglar.
	 */
	public String huella(long consultorioId) {
		StringBuilder canonico = new StringBuilder()
				.append(consultorioId).append('|')
				.append(personaId).append('|')
				.append(total.stripTrailingZeros().toPlainString());
		for (ImputacionPedida imputacion : imputaciones) {
			canonico.append('|').append(imputacion.obligacionId())
					.append(':').append(imputacion.importe().stripTrailingZeros().toPlainString());
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonico.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
