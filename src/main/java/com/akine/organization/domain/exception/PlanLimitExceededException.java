package com.akine.organization.domain.exception;

import com.akine.organization.spi.LimitCode;

/**
 * El alta solicitada excederia un limite del plan vigente (RF-M01-004).
 *
 * <p>Es una regla de negocio, no un problema de permisos: el actor tiene derecho a hacer la
 * operacion, lo que no alcanza es el plan contratado. Por eso la respuesta HTTP es 409 y no
 * 403.
 *
 * <p>Se lanza SOLO al dar de alta algo nuevo. Un downgrade jamas invalida datos existentes
 * (RN-M01-004): lo que ya existe sigue operativo y consultable aunque supere el limite nuevo.
 */
public class PlanLimitExceededException extends RuntimeException {

	private final LimitCode limitCode;
	private final Integer limitValue;
	private final long currentUsage;

	public PlanLimitExceededException(LimitCode limitCode, Integer limitValue, long currentUsage) {
		super("Limite del plan alcanzado: " + limitCode + " (limite " + limitValue
				+ ", uso actual " + currentUsage + ")");
		this.limitCode = limitCode;
		this.limitValue = limitValue;
		this.currentUsage = currentUsage;
	}

	public LimitCode getLimitCode() {
		return limitCode;
	}

	/** Tope del plan. {@code null} significaria ilimitado, y entonces no se lanza esta excepcion. */
	public Integer getLimitValue() {
		return limitValue;
	}

	public long getCurrentUsage() {
		return currentUsage;
	}
}
