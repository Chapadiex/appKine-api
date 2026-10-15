package com.akine.activity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * La venta de un pase (RF-M29-002). {@code idempotencyKey} es opcional; con ella, un reintento
 * con el mismo pedido devuelve la venta existente y uno con otro pedido es 409.
 */
public record VenderPaseCommand(long productoId, long personaId, String idempotencyKey) {

	/** Huella del pedido: sede, producto y titular. Lo demas lo decide el servidor. */
	public String huella(long consultorioId) {
		String canonico = consultorioId + "|" + productoId + "|" + personaId;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonico.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
