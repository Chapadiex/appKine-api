package com.akine.billing.application;

/**
 * Por que una prestacion no se puede reclamar (RF-M21-003).
 *
 * <p><b>El hallazgo viaja con motivo y no como un "no entra" a secas.</b> Los seis casos mandan al
 * administrativo a lugares distintos —uno se arregla cambiando el periodo del lote, otro exige
 * pasar la deuda al paciente— y colapsarlos obligaria a adivinar cual es. Es la misma leccion que
 * 05.01 dejo escrita con {@code MotivoSinSlots} y 03.05 con {@code MotivoSinArancel}.
 *
 * <h2>Lo que esta lista NO tiene, y por que</h2>
 *
 * <p>Faltan los tres requisitos documentales del convenio —orden, autorizacion y credencial—, que
 * es lo primero que RF-M21-003 sugiere validar. Viven en {@code ArancelCongelado} y el consumidor
 * que tenia que copiarlos a {@code obligacion} es el devengado, que <b>no se recableo contra
 * convenios</b>. Es la misma causa raiz por la que no existe ninguna obligacion de financiador, y
 * esta declarada en el design challenge de AKINE-07.04 y en {@code docs/tests-diferidos.md}.
 */
public enum HallazgoDeValidacion {

	/** La deuda se anulo. No se le reclama a nadie una obligacion que ya no existe. */
	OBLIGACION_ANULADA,

	/** Saldo cero: ya se cobro por otra via. Presentarla seria reclamar dos veces. */
	SIN_SALDO,

	/** Es deuda de otro financiador. Un lote se le manda a uno solo. */
	FINANCIADOR_DISTINTO,

	/** Se presto en otra sede. El convenio es contextual a la sede (RN-M16-001). */
	SEDE_DISTINTA,

	/** El devengado cae fuera del periodo declarado del lote. */
	FUERA_DEL_PERIODO,

	/** Otra moneda. Un total que suma pesos con dolares no significa nada. */
	MONEDA_DISTINTA
}
