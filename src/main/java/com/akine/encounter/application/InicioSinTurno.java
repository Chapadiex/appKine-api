package com.akine.encounter.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Lo que hace falta para abrir una atencion sin turno (U-24, DP-43).
 *
 * @param personaId el paciente; tiene que tener perfil de paciente vigente
 * @param ofertaId  que se presta, en la sede de la ruta
 * @param casoId    Caso Clinico de la atencion, o {@code null} si la oferta no lo exige
 * @param motivo    por que se atiende sin turno. Obligatorio
 */
public record InicioSinTurno(long personaId, long ofertaId, Long casoId, String motivo) {

	/**
	 * Huella del pedido para la idempotencia: misma clave con otra huella es otro pedido.
	 *
	 * <p>El motivo <b>no</b> entra: es texto libre, y reescribirlo en el reintento —un espacio, una
	 * coma— no convierte el doble click en una atencion distinta. Mismo criterio que la reserva de
	 * turno, cuya huella tampoco lleva el texto.
	 */
	public String huella(long consultorioId) {
		String canonico = consultorioId + "|" + personaId + "|" + ofertaId
				+ (casoId == null ? "" : "|c" + casoId);
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonico.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
