package com.akine.billing.domain;

/**
 * Que parte de una prestacion es una obligacion (AKINE F-4, RF-M18-002).
 *
 * <p>{@link Responsable} dice QUIEN debe; esto dice POR QUE. Hacen falta los dos porque el
 * paciente puede deber por dos motivos que el administrativo tiene que distinguir en la cuenta
 * corriente: la prestacion entera, porque no tenia cobertura, o el coseguro del convenio.
 *
 * <pre>
 *   PARTICULAR   paciente     precio de la oferta (sin cobertura aplicable: 07.01 tal cual)
 *   FINANCIADOR  financiador  importe_financiador del arancel congelado
 *   COSEGURO     paciente     coseguro del arancel congelado
 * </pre>
 *
 * <p>La correspondencia con el responsable la sostiene {@code ck_obligacion_concepto_responsable}
 * (V77), no solo el constructor.
 */
public enum ConceptoObligacion {

	PARTICULAR(Responsable.PACIENTE),
	FINANCIADOR(Responsable.FINANCIADOR),
	COSEGURO(Responsable.PACIENTE);

	private final Responsable responsable;

	ConceptoObligacion(Responsable responsable) {
		this.responsable = responsable;
	}

	public Responsable responsable() {
		return responsable;
	}
}
