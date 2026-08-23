package com.akine.organization.application;

import com.akine.organization.spi.LimitCode;

/**
 * Un limite del plan con el uso real del tenant.
 *
 * <p>Sirve para dos cosas distintas y por eso lleva {@code exceeded} explicito:
 * <ul>
 *   <li>mostrar la suscripcion con su consumo ("3 de 5 consultorios");</li>
 *   <li>avisar, al bajar de plan, que limites ya estan excedidos.</li>
 * </ul>
 *
 * <p>En el segundo caso es un AVISO y nada mas: un downgrade jamas borra, desactiva ni marca
 * invalido nada (RN-M01-004). Lo que ya existe sigue 100% operativo y consultable; lo unico
 * que cambia es que la proxima alta se rechaza.
 *
 * <p>Este conteo es informativo y se lee sin bloqueo. El conteo que DECIDE un alta es otro:
 * ocurre dentro de la transaccion del alta y despues de bloquear la suscripcion
 * ({@code PlanGate}). Confundirlos reintroduce la carrera de limites.
 */
public record LimitUsageView(LimitCode code, Integer limitValue, long currentUsage) {

	/** Indica si el uso actual ya supera el tope. Con tope nulo (ilimitado) nunca. */
	public boolean exceeded() {
		return limitValue != null && currentUsage > limitValue;
	}
}
