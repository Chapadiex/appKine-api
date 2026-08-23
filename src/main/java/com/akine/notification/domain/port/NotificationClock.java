package com.akine.notification.domain.port;

import java.time.Instant;

/**
 * El reloj del modulo, como puerto.
 *
 * <p>Existe para que el worker sea testeable: el backoff, el vencimiento del lease y el
 * agotamiento de intentos son todas reglas sobre el tiempo, y probarlas con
 * {@code Instant.now()} incrustado obliga a dormir el test o a aceptar que no se prueban.
 *
 * <p>Es un puerto propio y no {@code java.time.Clock} para no competir por un bean de un tipo
 * de la JDK que otro modulo podria querer definir con otra semantica.
 */
@FunctionalInterface
public interface NotificationClock {

	/** Instante actual, en UTC. */
	Instant now();
}
