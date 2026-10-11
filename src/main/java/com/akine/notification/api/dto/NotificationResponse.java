package com.akine.notification.api.dto;

import com.akine.notification.application.ConsultaDeNotificacionesService.NotificacionResumen;
import com.akine.notification.domain.OutboxStatus;
import com.akine.notification.spi.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Una notificacion del tenant, tal como la ve quien administra los reintentos.
 *
 * <p><b>Sin PHI</b>: ni el cuerpo ni el payload salen nunca, y el destinatario va enmascarado.
 */
@Schema(description = "Notificacion del tenant, sin contenido y con el destinatario enmascarado")
public record NotificationResponse(

		@Schema(description = "Identificador; es el que recibe retryNotification", example = "42")
		long id,

		@Schema(description = "Tipo de notificacion", example = "INVITACION_COLABORADOR")
		NotificationType tipo,

		@Schema(description = "Destinatario enmascarado: primera letra y dominio",
				example = "j***@ejemplo.test")
		String destinatario,

		@Schema(description = "Estado en el outbox", example = "AGOTADA")
		OutboxStatus estado,

		@Schema(description = "true si el estado admite retryNotification (FALLIDA o AGOTADA)",
				example = "true")
		boolean reintentable,

		@Schema(description = "Intentos de envio realizados", example = "5")
		int intentos,

		@Schema(description = "Intentos maximos antes de quedar AGOTADA", example = "5")
		int maxIntentos,

		@Schema(description = "Resumen sanitizado del ultimo error, sin direcciones, hosts ni "
				+ "tokens. Ausente si nunca fallo", example = "MailSendException: Connection refused")
		String ultimoError,

		@Schema(description = "Cuando se encolo") Instant creadaEn,

		@Schema(description = "Ultimo cambio de estado") Instant actualizadaEn,

		@Schema(description = "Proximo intento programado. Sin significado en estados terminales")
		Instant proximaEjecucionEn,

		@Schema(description = "Cuando se envio. Ausente si no se envio") Instant enviadaEn) {

	public static NotificationResponse de(NotificacionResumen resumen) {
		return new NotificationResponse(
				resumen.id(),
				resumen.tipo(),
				resumen.destinatarioEnmascarado(),
				resumen.estado(),
				resumen.reintentable(),
				resumen.intentos(),
				resumen.maxIntentos(),
				resumen.ultimoError(),
				resumen.creadaEn(),
				resumen.actualizadaEn(),
				resumen.proximaEjecucionEn(),
				resumen.enviadaEn());
	}
}
