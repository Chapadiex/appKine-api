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
 * @param renovacionAutomatica solo para un abono (AKINE-08.08): que el job venda el periodo siguiente
 *                     cuando este se acerque a su fin. Opt-in: por defecto nada se renueva solo
 */
public record VenderPaseCommand(
		long productoId, long personaId, String idempotencyKey,
		LocalDate periodoDesde, LocalDate periodoHasta, boolean renovacionAutomatica) {

	/** La venta de un pack, sin periodo. */
	public VenderPaseCommand(long productoId, long personaId, String idempotencyKey) {
		this(productoId, personaId, idempotencyKey, null, null, false);
	}

	/** La venta de un abono sin renovacion automatica (la forma de antes de 08.08). */
	public VenderPaseCommand(long productoId, long personaId, String idempotencyKey,
			LocalDate periodoDesde, LocalDate periodoHasta) {
		this(productoId, personaId, idempotencyKey, periodoDesde, periodoHasta, false);
	}

	/**
	 * Huella del pedido: sede, producto, titular, periodo y renovacion pedida. Lo demas lo decide el
	 * servidor. Sin periodo ni renovacion es la misma huella que antes de 08.08, asi que una clave
	 * vieja sigue reconociendose.
	 */
	public String huella(long consultorioId) {
		String canonico = consultorioId + "|" + productoId + "|" + personaId;
		if (periodoDesde != null || periodoHasta != null) {
			canonico += "|" + periodoDesde + "|" + periodoHasta;
		}
		if (renovacionAutomatica) {
			canonico += "|renovable";
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
