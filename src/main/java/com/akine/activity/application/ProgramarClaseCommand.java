package com.akine.activity.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Lo que hay que decir para programar una clase (RF-M28-001).
 *
 * @param inicio         instante de inicio, UTC
 * @param fin            instante de fin, UTC y EXCLUSIVO. <b>Se pide, no se deriva</b> de la
 *                       duracion de la oferta: una clase de Pilates de hora y media sobre una
 *                       oferta de 60 minutos es un caso real, y derivarlo obligaria a crear una
 *                       oferta por cada duracion
 * @param capacidad      capacidad propia de la clase (RN-M28-002). Se valida contra la efectiva
 * @param idempotencyKey clave del cliente para que un reintento no programe dos clases.
 *                       {@code null} la desactiva, y eso es legitimo
 */
public record ProgramarClaseCommand(
		Instant inicio,
		Instant fin,
		Long profesionalId,
		int capacidad,
		String titulo,
		String idempotencyKey) {

	/**
	 * Huella del pedido, para distinguir un reintento de un reuso de clave con otro contenido.
	 *
	 * <p>Con la clave sola, un cliente que la reusa para programar OTRA clase recibe en silencio
	 * la anterior y cree que programo la nueva. Con el hash, ese caso es un 409 explicito. Es la
	 * misma forma que {@code scheduling.application.ReservaCommand}.
	 *
	 * <p>La clave NO entra en el hash: lo que se compara es el CONTENIDO del pedido. El separador
	 * {@code |} tampoco es decoracion — sin el, {@code capacidad=1} con {@code profesionalId=23} y
	 * {@code capacidad=12} con {@code profesionalId=3} producirian la misma cadena.
	 */
	public String huella(long consultorioId, long ofertaId) {
		String canonico = consultorioId + "|" + ofertaId + "|" + inicio + "|" + fin
				+ "|" + profesionalId + "|" + capacidad;
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(canonico.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException imposible) {
			// SHA-256 es obligatorio en toda JVM desde Java 1.4. Si falta, el problema no es este
			// metodo y tragarse la excepcion solo lo escondera.
			throw new IllegalStateException("SHA-256 no disponible en esta JVM", imposible);
		}
	}
}
