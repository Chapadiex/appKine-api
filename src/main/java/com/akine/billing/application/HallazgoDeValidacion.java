package com.akine.billing.application;

/**
 * Por que una prestacion no se puede reclamar (RF-M21-003).
 *
 * <p><b>El hallazgo viaja con motivo y no como un "no entra" a secas.</b> Los siete casos mandan al
 * administrativo a lugares distintos —uno se arregla cambiando el periodo del lote, otro exige
 * pasar la deuda al paciente— y colapsarlos obligaria a adivinar cual es. Es la misma leccion que
 * 05.01 dejo escrita con {@code MotivoSinSlots} y 03.05 con {@code MotivoSinArancel}.
 *
 * <h2>Los requisitos documentales del convenio (AKINE-07.04b)</h2>
 *
 * <p>{@link #SIN_ORDEN}, {@link #SIN_AUTORIZACION} y {@link #CREDENCIAL_FALTANTE} se evaluan contra
 * lo que el convenio exigia el dia de la prestacion —congelado en la obligacion por F-4— y solo al
 * validar y al confirmar, no al incluir: el item tiene que estar en el lote para que la pantalla de
 * validacion lo muestre. Los dos primeros bloquean; la credencial avisa. Es la opcion recomendada
 * de la decision U-28, implementada como propuesta. Ver
 * docs/diseno/AKINE-07.04b-reparos-y-debito-parcial.md §2.
 */
public enum HallazgoDeValidacion {

	/** La deuda se anulo. No se le reclama a nadie una obligacion que ya no existe. */
	OBLIGACION_ANULADA,

	/** Saldo cero: ya se cobro por otra via. Presentarla seria reclamar dos veces. */
	SIN_SALDO,

	/**
	 * Es deuda del paciente, no de un financiador. Se cobra en mostrador; no va en ningun lote.
	 *
	 * <p>Separado de {@link #FINANCIADOR_DISTINTO} porque el remedio es el opuesto: aquella deuda
	 * se reclama igual, pero en el lote de su financiador.
	 */
	DEUDA_DEL_PACIENTE,

	/** Es deuda de otro financiador. Un lote se le manda a uno solo: va en el lote de ese. */
	FINANCIADOR_DISTINTO,

	/** Se presto en otra sede. El convenio es contextual a la sede (RN-M16-001). */
	SEDE_DISTINTA,

	/** El devengado cae fuera del periodo declarado del lote. */
	FUERA_DEL_PERIODO,

	/** Otra moneda. Un total que suma pesos con dolares no significa nada. */
	MONEDA_DISTINTA,

	/**
	 * El convenio exigia orden medica el dia de la prestacion y el paciente no tiene ninguna
	 * vigente ese dia para esa cobertura. <b>Reparo: bloquea confirmar</b> (AKINE-07.04b, U-28).
	 *
	 * <p>Se arregla cargando la orden en la ficha del paciente; el reparo se levanta solo en la
	 * proxima validacion.
	 */
	SIN_ORDEN,

	/**
	 * El convenio exigia autorizacion previa y no hay ninguna APROBADA de esa cobertura y esa
	 * practica cuya vigencia cubra el dia de la prestacion. <b>Reparo: bloquea confirmar.</b>
	 *
	 * <p>El financiador la va a debitar de todos modos: presentarla igual es un debito anunciado.
	 */
	SIN_AUTORIZACION,

	/**
	 * El convenio exigia credencial y estaba vencida el dia de la prestacion, o la cobertura no
	 * tiene numero de afiliado. <b>Aviso: NO bloquea</b> —viaja en {@code avisos}, no en
	 * {@code hallazgos}—, porque se suele regularizar despues del envio.
	 */
	CREDENCIAL_FALTANTE;

	/** Si este hallazgo impide confirmar el lote. Solo la credencial es un aviso. */
	public boolean bloquea() {
		return this != CREDENCIAL_FALTANTE;
	}
}
