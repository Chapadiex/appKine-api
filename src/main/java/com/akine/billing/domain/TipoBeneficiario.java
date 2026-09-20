package com.akine.billing.domain;

/**
 * Quien cobra el egreso.
 *
 * <p>La referencia del colaborador es su <b>membership</b> y no su cuenta, por lo mismo que V23 y
 * V28: la misma persona puede ser profesional en un centro y administrativa en otro, y lo que la
 * identifica dentro de esta organizacion es el vinculo.
 */
public enum TipoBeneficiario {

	/** Un vinculo de la organizacion: el kinesiologo, la recepcionista. */
	COLABORADOR,

	/** El contador, la inmobiliaria, la empresa de limpieza. Solo nombre y documento. */
	EXTERNO
}
