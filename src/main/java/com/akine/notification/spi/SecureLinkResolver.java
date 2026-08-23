package com.akine.notification.spi;

import java.util.Optional;

/**
 * Puerto de SALIDA invertido: {@code notification} lo declara y el modulo dueno del token
 * —{@code identity}— lo implementa.
 *
 * <p><b>Por que existe.</b> El outbox no guarda tokens ni enlaces (T-11), pero el mail tiene
 * que llevar uno. La unica forma de tener las dos cosas es reconstruir el enlace en el momento
 * del envio, a partir de la referencia opaca al token que si esta guardada. Este puerto es esa
 * reconstruccion.
 *
 * <p><b>Por que la flecha va asi.</b> Si {@code notification} importara {@code identity.spi}
 * para pedir el enlace, quedaria atado al modulo de identidad para siempre —y la notificacion
 * es un mecanismo generico que van a usar agenda, facturacion y clinica—. Declarando el puerto
 * aca, la dependencia es {@code identity -> notification.spi}: la misma direccion que ya tiene
 * el productor cuando encola, y el grafo sigue siendo aciclico.
 *
 * <p><b>El resultado es efimero por contrato.</b> Quien implemente este puerto devuelve el
 * enlace y no lo persiste en ningun lado; el worker lo usa para redactar el mail y lo descarta.
 * El enlace nunca vuelve al outbox, ni al log, ni al {@code error_sanitizado}.
 */
public interface SecureLinkResolver {

	/**
	 * Reconstruye el enlace de un solo uso para el token referenciado.
	 *
	 * <p>Devuelve {@link Optional#empty()} si el token ya no sirve: fue consumido, fue
	 * revocado o expiro. Eso NO es un fallo transitorio y el worker no lo reintenta —reenviar
	 * un enlace muerto no ayuda a nadie—: la fila pasa a {@code AGOTADA} con el motivo
	 * explicito y la persona pide uno nuevo (T-11).
	 *
	 * @param tipo              tipo de notificacion, que determina la ruta del enlace
	 * @param referenciaTokenId ID opaco del token, tal como se guardo al encolar
	 */
	Optional<String> resolveLink(NotificationType tipo, String referenciaTokenId);
}
