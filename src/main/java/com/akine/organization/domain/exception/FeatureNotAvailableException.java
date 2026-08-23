package com.akine.organization.domain.exception;

import com.akine.organization.spi.FeatureCode;

/**
 * La funcionalidad pedida no esta incluida en el plan vigente de la organizacion
 * (RF-M01-004).
 *
 * <p>Como en {@link PlanLimitExceededException}, es una regla de negocio y no un problema de
 * permisos: el actor esta autorizado, el plan no incluye la funcion. La respuesta es 409.
 */
public class FeatureNotAvailableException extends RuntimeException {

	private final FeatureCode featureCode;

	public FeatureNotAvailableException(FeatureCode featureCode) {
		super("Funcionalidad no incluida en el plan vigente: " + featureCode);
		this.featureCode = featureCode;
	}

	public FeatureCode getFeatureCode() {
		return featureCode;
	}
}
