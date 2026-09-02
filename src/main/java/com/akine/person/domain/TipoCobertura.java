package com.akine.person.domain;

/**
 * Modalidad de una cobertura del paciente (M08).
 *
 * <p><b>{@link #PARTICULAR} es la AUSENCIA de plan financiado, no un financiador especial.</b>
 * RN-M15-004 lo declara y V41 explica por que: sembrar un financiador llamado PARTICULAR lo
 * volveria borrable, renombrable y duplicable, y obligaria a que una decision del sistema
 * dependiera de un nombre. Por eso RN-M08-001 —"PARTICULAR siempre debe estar disponible"— no
 * necesita ninguna fila para ser verdad: no hay nada que dar de baja.
 */
public enum TipoCobertura {

	/** Sin financiador: el paciente paga. Sus columnas de referencia congelada quedan en null. */
	PARTICULAR,

	/** Con plan de un financiador, congelado al firmar. */
	FINANCIADA
}
