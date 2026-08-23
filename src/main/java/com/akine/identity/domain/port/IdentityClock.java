package com.akine.identity.domain.port;

import java.time.Instant;

/**
 * El reloj del modulo, como puerto.
 *
 * <p>Existe por el mismo motivo que {@code NotificationClock} en {@code notification}: la
 * sesion es un conjunto de reglas sobre el tiempo —vencimiento absoluto del refresh, rotacion,
 * vigencia de la membership al cambiar de contexto— y probarlas con {@code Instant.now()}
 * incrustado obliga a dormir el test o a aceptar que no se prueban. "Un refresh que vencio hace
 * un segundo" no es un caso que se pueda escribir de otra forma.
 *
 * <p>Es un puerto propio y no {@code java.time.Clock} para no competir por un bean de un tipo
 * de la JDK que otro modulo podria querer definir con otra semantica: {@code platform} ya pasa
 * un {@code Clock} a mano a {@code TenantContextFilter} sin publicarlo como bean.
 *
 * <p><b>Deuda conocida:</b> los servicios de identidad anteriores a esta pieza
 * ({@code AuthenticationService}, {@code PasswordResetService}, {@code AccountAdminService})
 * siguen llamando a {@code Instant.now()} directamente. Migrarlos a este puerto es un refactor
 * que excede el alcance de la pieza de sesion y queda anotado en el registro de cierre.
 */
@FunctionalInterface
public interface IdentityClock {

	/** Instante actual, en UTC. */
	Instant now();
}
