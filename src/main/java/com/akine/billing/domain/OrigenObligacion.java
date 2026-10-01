package com.akine.billing.domain;

/**
 * Que hecho devengo una deuda (AKINE-08.06, {@code V64}).
 *
 * <p>Hasta 07.01 habia uno solo y estaba implicito en una columna {@code NOT NULL}:
 * {@code obligacion.sesion_id}. RF-M18-009 pide registrar la deuda al <b>comprar</b> creditos
 * anticipados, y una compra no tiene sesion — ese es el impedimento real que M29 encontro en el
 * esquema de 07.01.
 *
 * <p>Se resolvio expandiendo {@code obligacion} y no creando una tabla de deuda paralela: una
 * segunda tabla seria una segunda cuenta corriente para la misma persona, y el cobro de 07.02
 * —que imputa contra {@code obligacion}— no veria la mitad.
 *
 * <p><b>Cada origen tiene su propia columna y su propio unique</b>, en vez de un par generico
 * {@code (origen_tipo, origen_id)} sin FK: asi el motor sigue garantizando que la deuda apunta a
 * algo que existe, y cada idempotencia es un unique real.
 */
public enum OrigenObligacion {

	/**
	 * Una prestacion concretada. Es lo unico que existia hasta AKINE-08.06 y lo que escribe
	 * {@code ObligacionDevengador} al cerrarse una sesion (RF-M18-001, RN-M18-001).
	 *
	 * <p>Materializado por {@code sesion_id}; su idempotencia es
	 * {@code uk_obligacion_prestacion}.
	 */
	SESION,

	/**
	 * La compra de un pack de creditos (RF-M18-009, RF-M29-002).
	 *
	 * <p>Materializado por {@code pase_id}; su idempotencia es {@code uk_obligacion_venta}, que es
	 * lo que hace que <b>un reintento de la compra no pueda crear una segunda deuda</b> aunque
	 * alguien escriba manana otro camino de devengo por venta.
	 */
	VENTA_PASE,

	/**
	 * La venta de un abono por periodo. <b>Declarado y sin emisor: es AKINE-08.08.</b>
	 *
	 * <p>Esta en el enum y en el {@code CHECK} de {@code V64} desde hoy para que esa etapa no
	 * tenga que hacer {@code DROP CHECK} + {@code ADD CONSTRAINT}, que reescribe la lista entera
	 * y es exactamente la operacion con la que {@code V57} borro en silencio un valor que
	 * {@code V56} acababa de agregar.
	 */
	VENTA_ABONO;

	/** La deuda nace de vender algo, no de prestar una atencion. */
	public boolean esVenta() {
		return this != SESION;
	}
}
