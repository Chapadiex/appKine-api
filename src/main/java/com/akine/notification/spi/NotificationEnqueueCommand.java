package com.akine.notification.spi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lo que un modulo productor le pide al outbox: "cuando esta transaccion comitee, mandale
 * esto a esta persona".
 *
 * <p><b>Lo que este comando NO acepta, a proposito.</b> No hay campo para un token, ni para un
 * enlace, ni para una URL. Es deliberado: el outbox se consulta para diagnosticar entregas
 * fallidas, se reintenta desde una pantalla administrativa y termina en los backups, asi que
 * un token de activacion o de reset guardado ahi es una credencial persistida —exactamente lo
 * que RN-M02-003 prohibe— (T-11, challenge D-6). Lo unico que viaja es
 * {@link #referenciaTokenId()}: el ID del token de un solo uso, no su valor. El enlace se
 * reconstruye en el momento del envio a traves de {@link SecureLinkResolver}.
 *
 * <p><b>{@code datosDeRender}</b> lleva solo lo minimo para redactar el mensaje (nombre de la
 * persona, nombre de la organizacion): RN-M26-002. Las claves estan en una lista blanca y los
 * valores se validan; cualquier cosa con pinta de URL, token o secreto hace fallar la
 * insercion en vez de quedar guardada.
 *
 * <p><b>{@code claveIdempotente}</b> es la garantia de RF-M26-005 / RN-M26-003: si el
 * productor reintenta su transaccion, la segunda insercion choca contra el UNIQUE y no se
 * crea una segunda notificacion. Debe derivarse de algo estable del hecho de negocio
 * (p.ej. {@code "activacion-cuenta:" + tokenId}), nunca de un random ni del reloj.
 *
 * <p><b>{@code organizationId} puede ser {@code null}</b>: hay notificaciones previas al
 * tenant —activacion de una cuenta invitada, recuperacion de contrasena— en las que la
 * persona todavia no tiene organizacion.
 *
 * @param tipo             que se envia; determina el template
 * @param destinatario     email destino
 * @param claveIdempotente clave estable del hecho de negocio; UNIQUE en la tabla
 * @param organizationId   tenant al que se atribuye, o {@code null} si el hecho es previo al tenant
 * @param referenciaTokenId ID OPACO del token de un solo uso (jamas su valor); {@code null} si
 *                          el tipo no requiere enlace
 * @param datosDeRender    datos no sensibles para el template; claves de la lista blanca
 */
public record NotificationEnqueueCommand(
		NotificationType tipo,
		String destinatario,
		String claveIdempotente,
		Long organizationId,
		String referenciaTokenId,
		Map<String, String> datosDeRender) {

	public NotificationEnqueueCommand {
		datosDeRender = datosDeRender == null
				? Map.of()
				: Map.copyOf(new LinkedHashMap<>(datosDeRender));
	}

	/** Atajo para las notificaciones sin datos de render adicionales. */
	public static NotificationEnqueueCommand of(
			NotificationType tipo,
			String destinatario,
			String claveIdempotente,
			Long organizationId,
			String referenciaTokenId) {
		return new NotificationEnqueueCommand(
				tipo, destinatario, claveIdempotente, organizationId, referenciaTokenId, Map.of());
	}
}
