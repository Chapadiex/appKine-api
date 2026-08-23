package com.akine.notification;

import com.akine.notification.domain.NotificationOutboxEntry;
import com.akine.notification.domain.OutboxStatus;
import com.akine.notification.domain.SanitizedPayload;
import com.akine.notification.spi.NotificationType;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Filas del outbox en cada estado, para tests sin base.
 *
 * <p>Mismo criterio que {@code IdentityFixtures}: el id lo pone la base y la entity no expone
 * setter, asi que en un test se asigna por reflexion. Un setter "solo para los tests" es como
 * termina existiendo en produccion.
 *
 * <p><b>Los estados se arman recorriendo transiciones legales</b>, nunca escribiendo el campo
 * {@code estado} por reflexion: si se forzara, un test podria estar probando una fila que la
 * maquina de estados no permite construir, y pasaria mientras produccion nunca llega ahi.
 *
 * <p>Datos sinteticos sin excepcion: ninguna direccion real, ningun token real.
 */
public final class NotificationFixtures {

	public static final long ENTRY_ID = 700L;
	public static final long ORG_ID = 10L;

	public static final String DESTINATARIO = "ana.gomez@ejemplo.test";
	public static final String TOKEN_REF = "ref-token-0001";
	public static final String CLAVE = "activacion-cuenta:ref-token-0001";

	/** Instante fijo: un test de backoff con el reloj real es un test que a veces pasa. */
	public static final Instant AHORA = Instant.parse("2026-01-15T10:00:00Z");

	private NotificationFixtures() {
	}

	public static <T> T conId(T entidad, long id) {
		ReflectionTestUtils.setField(entidad, "id", id);
		return entidad;
	}

	/** Fila recien encolada, con id asignado. */
	public static NotificationOutboxEntry pendiente() {
		return conId(nueva(NotificationType.ACTIVACION_CUENTA, SanitizedPayload.vacio()), ENTRY_ID);
	}

	/** Fila recien encolada del tipo pedido, sin id. */
	public static NotificationOutboxEntry nueva(NotificationType tipo, SanitizedPayload payload) {
		return new NotificationOutboxEntry(
				tipo,
				DESTINATARIO,
				CLAVE,
				ORG_ID,
				tipo.requiereEnlaceSeguro() ? TOKEN_REF : null,
				payload,
				5,
				AHORA);
	}

	/** Payload de render valido, con las tres claves de la lista blanca. */
	public static SanitizedPayload payloadCompleto() {
		return SanitizedPayload.of(Map.of(
				"nombre", "Ana",
				"organizacionNombre", "Kine Sur",
				"invitadoPor", "Dr. Ruiz"));
	}

	/**
	 * Fila en el estado pedido, alcanzada por transiciones validas.
	 *
	 * @param estado estado destino
	 */
	public static NotificationOutboxEntry en(OutboxStatus estado) {
		NotificationOutboxEntry entry = pendiente();
		if (estado == OutboxStatus.PENDIENTE) {
			return entry;
		}
		entry.marcarEnProceso(AHORA);
		switch (estado) {
			case PROCESANDO -> {
				// Ya esta.
			}
			case ENVIADA -> entry.marcarEnviada(AHORA);
			case FALLIDA -> entry.registrarFalloPermanente("destinatario invalido");
			case REINTENTABLE -> entry.registrarFalloTransitorio(
					AHORA, "smtp caido", Optional.of(Duration.ofMinutes(1)));
			case AGOTADA -> entry.registrarFalloTransitorio(AHORA, "smtp caido", Optional.empty());
			default -> throw new IllegalArgumentException("Estado no contemplado: " + estado);
		}
		return entry;
	}
}
