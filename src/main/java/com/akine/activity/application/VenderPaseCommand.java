package com.akine.activity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;

/**
 * La venta de un pase o de un periodo de abono (RF-M29-002, RF-M29-007). {@code idempotencyKey} es
 * opcional; con ella, un reintento con el mismo pedido devuelve la venta existente y uno con otro
 * pedido es 409.
 *
 * @param periodoDesde solo para un abono: primer dia del periodo que cubre. Obligatorio en ese caso
 * @param periodoHasta solo para un abono: ultimo dia, inclusive. Ausente: el periodo dura lo que el
 *                     producto ({@code vigenciaDias})
 */
public record VenderPaseCommand(
		long productoId, long personaId, String idempotencyKey,
		LocalDate periodoDesde, LocalDate periodoHasta) {

	/** La venta de un pack, sin periodo. */
	public VenderPaseCommand(long productoId, long personaId, String idempotencyKey) {
		this(productoId, personaId, idempotencyKey, null, null);
	}

	/**
	 * Huella del pedido: sede, producto, titular y periodo. Lo demas lo decide el servidor. Sin
	 * periodo es la misma huella que antes de 08.08, asi que una clave vieja sigue reconociendose.
	 */
	public String huella(long consultorioId) {
		String canonico = consultorioId + "|" + productoId + "|" + personaId;
		if (periodoDesde != null || periodoHasta != null) {
			canonico += "|" + periodoDesde + "|" + periodoHasta;
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonico.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
