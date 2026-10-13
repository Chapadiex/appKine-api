package com.akine.notification.spi;

import java.util.Optional;

/**
 * Puerto de SALIDA invertido: {@code notification} lo declara y cada modulo dueno de un hecho
 * que se puede deshacer lo implementa. Misma direccion que {@link SecureLinkResolver}: la flecha
 * es {@code modulo -> notification.spi}, la que ya existe cuando ese modulo encola.
 *
 * <h2>El defecto que lo motivo</h2>
 *
 * <p>El outbox redacta el correo con lo que se congelo al encolar. Si el relay esta caido, la
 * fila queda {@code REINTENTABLE} minutos u horas, y mientras tanto el hecho que la origino puede
 * deshacerse: un administrador <b>cancela la invitacion</b>. Cuando el relay volvia, el correo
 * salia igual —{@code ENVIADA}— con un enlace que ya no abria nada (la vista previa daba 404).
 * Lo encontro la validacion de la guia de QA (PR #95).
 *
 * <p>Por eso el worker pregunta, <b>justo antes de cada intento</b>, si la notificacion sigue
 * teniendo sentido. Si no, la fila termina {@code AGOTADA} con el motivo que devuelve este puerto
 * y no se manda nada: es lo que dice {@code V9} ("el envio se marca AGOTADA con motivo
 * explicito: reenviar un enlace muerto no sirve", T-11).
 *
 * <h2>Contrato</h2>
 *
 * <ul>
 *   <li><b>Solo lectura.</b> Preguntar no consume ni modifica nada del modulo dueno.</li>
 *   <li><b>Vacio = vigente.</b> Un tipo que el implementador no evalua es vigente; ante la duda,
 *       se envia. Callar un aviso legitimo es peor que mandar uno de mas.</li>
 *   <li><b>El motivo es legible y sin datos personales</b>: lo ve un administrador en el
 *       listado de notificaciones. Igual pasa por el sanitizador antes de guardarse.</li>
 *   <li>Una excepcion se trata como fallo <b>transitorio</b>: la fila vuelve a la cola y se
 *       pregunta de nuevo en el proximo intento.</li>
 * </ul>
 */
public interface VigenciaDeNotificacion {

	/**
	 * Por que la notificacion ya no debe enviarse, o vacio si sigue vigente.
	 *
	 * @param notificacion lo que el outbox sabe de la fila; nunca el enlace ni el token
	 */
	Optional<String> motivoDeNoVigencia(NotificacionEnCurso notificacion);

	/**
	 * Lo que se le muestra al implementador de una fila a punto de enviarse.
	 *
	 * @param tipo              tipo de la notificacion
	 * @param organizationId    tenant de la fila, o {@code null} si es previa al tenant
	 * @param claveIdempotente  la clave que el propio productor armo al encolar; es lo que
	 *                          identifica el hecho de negocio, y quien la parsea es quien la armo
	 * @param referenciaTokenId referencia opaca al token, o {@code null}
	 */
	record NotificacionEnCurso(
			NotificationType tipo,
			Long organizationId,
			String claveIdempotente,
			String referenciaTokenId) {
	}
}
