package com.akine.activity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Lo que hay que decir para inscribir a alguien (RF-M28-002).
 *
 * @param personaId        persona del padron. <b>No se exige perfil de paciente</b>: anotarse en
 *                         una clase no es entrar al circuito clinico (RF-M07-010)
 * @param aceptaListaEspera si no hay lugar, {@code true} encola y {@code false} devuelve un
 *                          {@code clase-completa}. <b>Se pide y no se asume</b>: encolar a alguien
 *                          que queria un lugar y ahora no sabe si lo tiene es peor que un error,
 *                          y reservarle un lugar a quien pidio la cola tambien
 * @param idempotencyKey   clave del cliente para que un reintento no inscriba dos veces.
 *                         {@code null} la desactiva, y eso es legitimo
 */
public record InscribirCommand(
		long personaId,
		boolean aceptaListaEspera,
		String idempotencyKey) {

	/**
	 * Huella del pedido. Ver {@code ProgramarClaseCommand#huella}: con la clave sola, un cliente
	 * que la reusa para inscribir a OTRA persona recibe en silencio la inscripcion anterior y cree
	 * que anoto a la nueva.
	 *
	 * <p>{@code aceptaListaEspera} NO entra en la huella, y es deliberado: es una preferencia
	 * sobre que hacer si no hay lugar, no parte de lo que se pide. Reintentar el mismo alta con la
	 * preferencia cambiada sigue siendo el mismo alta, y meterla en el hash convertiria un
	 * reintento legitimo en un 409.
	 */
	public String huella(long claseId) {
		String canonico = claseId + "|" + personaId;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonico.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
