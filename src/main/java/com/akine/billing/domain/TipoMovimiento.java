package com.akine.billing.domain;

/**
 * Que clase de hecho monetario es el movimiento, y en que direccion.
 *
 * <h2>El signo lo da el tipo, nunca el numero</h2>
 *
 * <p>{@code movimiento_caja.importe} es <b>siempre positivo</b>. Un ledger con numeros negativos
 * obliga a todo lector a conocer el signo de cada tipo para no sumar al reves, y basta que uno se
 * equivoque para que un arqueo cierre por casualidad. Es la misma decision que
 * {@code autorizacion_movimiento} tomo en 04.05.
 *
 * <h2>Cuatro valores y no dos</h2>
 *
 * <p>La reversion no reutiliza el tipo opuesto: revertir un ingreso <b>no</b> es lo mismo que
 * registrar un egreso, aunque el saldo se mueva igual. Un egreso es plata que se gasto; una
 * reversion es plata que nunca debio haber entrado. Colapsarlos haria imposible responder "cuanto
 * salio realmente de esta caja", y RF-M24-006 pide justamente poder mostrar las anulaciones
 * economicas como lo que son.
 */
public enum TipoMovimiento {

	/** Entro plata. */
	INGRESO(1),

	/** Salio plata. */
	EGRESO(-1),

	/** Compensa un {@link #INGRESO} anterior: la plata se devuelve o nunca debio entrar. */
	REVERSION_DE_INGRESO(-1),

	/** Compensa un {@link #EGRESO} anterior: la plata vuelve al cajon. */
	REVERSION_DE_EGRESO(1);

	private final int signo;

	TipoMovimiento(int signo) {
		this.signo = signo;
	}

	/** {@code +1} suma al saldo, {@code -1} resta. Ver el javadoc de la clase. */
	public int signo() {
		return signo;
	}

	public boolean esReversion() {
		return this == REVERSION_DE_INGRESO || this == REVERSION_DE_EGRESO;
	}

	/**
	 * El tipo que compensa a este.
	 *
	 * <p><b>Una reversion no se revierte.</b> Para deshacer una reversion se asienta un movimiento
	 * nuevo, que es honesto sobre lo que paso: encadenar reversiones de reversiones produce un
	 * historial que nadie puede leer, y el unique de la base ya impide revertir dos veces el mismo
	 * movimiento.
	 *
	 * @throws IllegalStateException si este tipo ya es una reversion
	 */
	public TipoMovimiento reversion() {
		return switch (this) {
			case INGRESO -> REVERSION_DE_INGRESO;
			case EGRESO -> REVERSION_DE_EGRESO;
			default -> throw new IllegalStateException(
					"Una reversion no se revierte: " + this);
		};
	}
}
