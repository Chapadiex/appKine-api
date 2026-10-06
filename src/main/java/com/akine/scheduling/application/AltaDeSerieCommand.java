package com.akine.scheduling.application;

import com.akine.scheduling.domain.ReglaDeRecurrencia;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Pedido de alta de una serie de turnos (AKINE E-3). */
public record AltaDeSerieCommand(
		long ofertaId,
		long personaId,
		Long profesionalId,
		ReglaDeRecurrencia regla,
		String idempotencyKey) {

	/**
	 * Huella del pedido para la idempotencia: misma clave con otro contenido es 409, no un replay de
	 * la serie anterior. Misma decision que {@link ReservaCommand#huella}.
	 */
	public String huella(long consultorioId) {
		String canonico = consultorioId + "|" + ofertaId + "|" + personaId + "|" + profesionalId
				+ "|" + regla.diasComoTexto() + "|" + regla.hora() + "|" + regla.desde()
				+ "|" + regla.hasta() + "|" + regla.cantidad();
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonico.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
