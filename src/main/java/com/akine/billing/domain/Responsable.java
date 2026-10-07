package com.akine.billing.domain;

/**
 * Quien debe.
 *
 * <p>Hasta AKINE F-4 solo existia PACIENTE: DP-10 fijo cobertura PARTICULAR y dejo afuera
 * financiadores y convenios, pero corto alcance y no modelo. Desde F-4 una prestacion cubierta por
 * un convenio son DOS filas con el mismo {@code sesion_id} y responsables distintos —la parte del
 * financiador y el coseguro del paciente—, que es exactamente lo que el unique de V36 permite. El
 * motivo de la deuda lo dice {@link ConceptoObligacion}.
 */
public enum Responsable {
	PACIENTE,
	FINANCIADOR
}
