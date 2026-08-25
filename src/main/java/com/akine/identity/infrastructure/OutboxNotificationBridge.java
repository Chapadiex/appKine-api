package com.akine.identity.infrastructure;

import com.akine.identity.domain.port.NotificationOutboxPort;
import com.akine.notification.spi.NotificationEnqueueCommand;
import com.akine.notification.spi.NotificationOutbox;
import com.akine.notification.spi.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Puente entre el puerto de {@code identity} y el {@code spi} de {@code notification}.
 *
 * <p>La flecha es {@code identity -> notification.spi}, la unica permitida: {@code notification}
 * no conoce este adaptador ni ninguna clase de identidad.
 *
 * <p><b>{@code Propagation.MANDATORY}</b>, igual que el {@code spi} que envuelve. No abre
 * transaccion propia y exige la del llamador: asi la fila del outbox se confirma junto con el
 * alta —o el reset— o no se confirma ninguna. Con {@code REQUIRED}, un llamador sin transaccion
 * comitearia el correo por su cuenta y un fallo posterior dejaria enviado un aviso sobre algo
 * que nunca paso.
 *
 * <h2>Lo unico que este adaptador hace de fondo: separar el enlace del payload</h2>
 *
 * <p>La {@code Notificacion} de {@code identity} trae el enlace ya construido; el comando de
 * {@code notification} no acepta enlaces ni tokens, por diseño (T-11). El adaptador reconcilia
 * las dos cosas:
 * <ul>
 *   <li>a la tabla van el tipo, el destinatario, los datos de render y la <b>referencia opaca
 *       al token</b> —el id de la fila de {@code token_verificacion}, extraido de la clave
 *       idempotente—, nada mas;</li>
 *   <li>el enlace no se persiste en ningun lado: queda en {@link SecureLinkVault}, en memoria,
 *       hasta que el worker lo pida al enviar.</li>
 * </ul>
 *
 * <p>El resultado es el que pide la regla: la credencial no entra a la base, ni al payload que
 * se consulta para diagnosticar entregas, ni a los backups.
 */
@Component
public class OutboxNotificationBridge implements NotificationOutboxPort {

	private static final Logger log = LoggerFactory.getLogger(OutboxNotificationBridge.class);

	/** Separa el prefijo del hecho de negocio del id del token: activacion + ":" + 42. */
	private static final char SEPARADOR_CLAVE = ':';

	private final NotificationOutbox notificationOutbox;
	private final SecureLinkVault secureLinkVault;

	public OutboxNotificationBridge(
			NotificationOutbox notificationOutbox, SecureLinkVault secureLinkVault) {
		this.notificationOutbox = notificationOutbox;
		this.secureLinkVault = secureLinkVault;
	}

	/**
	 * Sin transaccion a proposito: no escribe nada, solo valida. Exigir una transaccion aca
	 * obligaria a los casos de uso a abrirla antes de poder rechazar un pedido invalido, que es
	 * lo contrario de validar en el borde.
	 */
	@Override
	public void validarDatosPlantilla(java.util.Map<String, String> datosPlantilla) {
		notificationOutbox.validarDatosDeRender(datosPlantilla);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void encolar(Notificacion notificacion) {
		if (notificacion == null) {
			throw new IllegalArgumentException("La notificacion a encolar es obligatoria");
		}

		NotificationType tipo = traducir(notificacion.tipo());
		String referenciaTokenId = referenciaDe(notificacion.claveIdempotente(), tipo);

		notificationOutbox.enqueue(new NotificationEnqueueCommand(
				tipo,
				notificacion.destinatario(),
				notificacion.claveIdempotente(),
				notificacion.organizationId(),
				referenciaTokenId,
				notificacion.datosPlantilla()));

		// El enlace se guarda DESPUES de encolar y fuera de la base. Si esta transaccion se
		// revierte, el token de verificacion tampoco existe, asi que el enlace que queda en
		// memoria no abre nada: quien lo pida va a encontrar una fila de token inexistente.
		if (notificacion.enlaceSeguro() != null) {
			secureLinkVault.guardar(referenciaTokenId, notificacion.enlaceSeguro(), Instant.now());
		}

		log.debug("Notificacion de identidad encolada: tipo={} referenciaTokenId={}",
				tipo, referenciaTokenId);
	}

	/**
	 * Traduce el tipo de {@code identity} al del {@code spi}.
	 *
	 * <p>Explicito y exhaustivo: si {@code identity} agrega un correo y nadie lo mapea, esto
	 * deja de compilar. Un {@code valueOf} por nombre fallaria recien en produccion, y sobre
	 * un correo que ya no se puede volver a disparar.
	 */
	private static NotificationType traducir(TipoNotificacion tipo) {
		return switch (tipo) {
			case ACTIVACION_CUENTA -> NotificationType.ACTIVACION_CUENTA;
			case CUENTA_YA_REGISTRADA -> NotificationType.CUENTA_YA_REGISTRADA;
			case RESET_PASSWORD -> NotificationType.RECUPERACION_PASSWORD;
			case INVITACION_COLABORADOR -> NotificationType.INVITACION_COLABORADOR;
		};
	}

	/**
	 * Extrae el id del token de la clave idempotente: de "activacion:42" saca "42".
	 *
	 * <p>Los productores de {@code identity} arman esa clave con el id de la fila del token
	 * justamente porque es lo unico estable del hecho de negocio. Aprovecharla evita agregarle
	 * un campo al puerto —que vive en {@code domain} y no es de esta etapa— para transportar un
	 * dato que ya viaja.
	 *
	 * @return {@code null} para los tipos que no llevan enlace; el {@code spi} rechaza el
	 *         encolado si un tipo que si lo necesita llega sin referencia, y esa validacion es
	 *         la que queremos que actue
	 */
	private static String referenciaDe(String claveIdempotente, NotificationType tipo) {
		if (!tipo.requiereEnlaceSeguro()) {
			return null;
		}
		int separador = claveIdempotente.lastIndexOf(SEPARADOR_CLAVE);
		return separador < 0 || separador == claveIdempotente.length() - 1
				? claveIdempotente
				: claveIdempotente.substring(separador + 1);
	}
}
