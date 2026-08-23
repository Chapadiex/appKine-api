package com.akine.organization.spi;

/**
 * Resultado de evaluar un limite del plan contra el uso real.
 *
 * <p>Se usa en dos lugares con el mismo significado y por eso es un solo tipo:
 * <ul>
 *   <li>{@link PlanGate#evaluateCreationAndLock} lo devuelve con {@code allowed = true}. Nunca
 *       lo devuelve con {@code allowed = false}: un rechazo es una excepcion, no un valor de
 *       retorno que el llamador pueda ignorar por descuido.</li>
 *   <li>El cambio de plan lo devuelve con {@code allowed = false} para cada limite que el uso
 *       actual ya excede. Ahi es un AVISO, no un rechazo: un downgrade jamas toca datos
 *       existentes (RN-M01-004), solo hace que la proxima alta se rechace.</li>
 * </ul>
 *
 * @param limit        limite evaluado
 * @param limitValue   tope del plan. {@code null} = ilimitado explicito, que no es lo mismo
 *                     que no tener el limite configurado
 * @param currentUsage recursos activos contados DENTRO de la transaccion y DESPUES del
 *                     bloqueo de la suscripcion. Ver {@link PlanGate}
 * @param allowed      si un alta mas cabe
 */
public record PlanDecision(
		LimitCode limit,
		Integer limitValue,
		long currentUsage,
		boolean allowed) {

	/** Indica si el plan declara el limite sin tope. */
	public boolean unlimited() {
		return limitValue == null;
	}
}
