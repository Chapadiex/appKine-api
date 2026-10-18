package com.akine.billing.domain;

/**
 * Que le paso a una prestacion dentro del lote.
 *
 * <h2>Tres ocupan y tres liberan</h2>
 *
 * <p>RN-M21-003 —"una prestacion no debe duplicarse en presentaciones incompatibles"— no se cumple
 * con un {@code if}: la hace cumplir la columna <b>generada</b> {@code ocupa_marca} de V56 (redefinida en
 * V110), que vale 1 para {@link #INCLUIDO}, {@link #ACEPTADO} y {@link #DEBITADO_PARCIAL} y NULL
 * para los otros tres, mas el unique
 * {@code (organization_id, obligacion_id, ocupa_marca)}. Varios NULL no colisionan en MySQL.
 *
 * <p>Al ser generada <b>no puede desincronizarse de este enum</b>, y un camino futuro que debite o
 * anule un item libera la obligacion sin acordarse de hacerlo. Ese es el punto entero: un booleano
 * que alguien setea es un booleano que alguien olvida en el segundo camino que agregue.
 */
public enum EstadoItemPresentacion {

	/** Se reclamo y el financiador todavia no se expidio. <b>Ocupa</b> la obligacion. */
	INCLUIDO,

	/**
	 * El financiador lo acepto y la deuda quedo saldada. <b>Ocupa</b> la obligacion.
	 *
	 * <p>Se asigna <b>al conciliar</b>, nunca al recibir un pago: un pago es un importe global y no
	 * viene con el detalle de que prestaciones cubre. Ver {@code PresentacionService.conciliar}.
	 */
	ACEPTADO,

	/**
	 * El financiador lo rechazo, con motivo (RF-M21-006). <b>Libera</b> la obligacion.
	 *
	 * <p>RN-M21-004: rechazar una prestacion no elimina la sesion original, y tampoco perdona la
	 * deuda. La obligacion queda pendiente y disponible para otro lote — que es el caso borde
	 * "reapertura" de la etapa.
	 *
	 * <p>Desde V110 es el debito <b>total</b> (importe igual a lo presentado). El parcial es
	 * {@link #DEBITADO_PARCIAL}. Las filas anteriores a V110 pueden tener un importe menor: siguen
	 * con la regla vieja y no saldan nada al conciliar (docs/diseno/AKINE-07.04b §3.4).
	 */
	DEBITADO,

	/**
	 * El financiador rechazo <b>parte</b> de lo reclamado, con motivo, y el lote sigue en curso
	 * (AKINE-07.04b, V110). <b>Ocupa</b> la obligacion.
	 *
	 * <p>Sigue ocupando porque lo NO debitado todavia se esta cobrando en este lote: si la liberara,
	 * otro lote podria reclamar la obligacion entera —por su saldo completo— mientras este va a
	 * saldar una parte al conciliar. Ese es el reclamo doble que RN-M21-003 prohibe.
	 */
	DEBITADO_PARCIAL,

	/**
	 * El lote se concilio con este item debitado en parte (AKINE-07.04b, V110). <b>Libera</b> la
	 * obligacion.
	 *
	 * <p>La conciliacion salda lo cobrado —presentado menos debitado— y lo debitado queda
	 * pendiente en la obligacion, disponible para otro lote. Re-presentarlo crea otra fila, por el
	 * saldo que quedo: la historia de los dos reclamos se conserva.
	 */
	ACEPTADO_PARCIAL,

	/** El borrador que lo contenia se descarto. <b>Libera</b> la obligacion. */
	ANULADO;

	/** Si esta fila mantiene tomada su obligacion. Espeja {@code ocupa_marca} de V110. */
	public boolean ocupa() {
		return this == INCLUIDO || this == ACEPTADO || this == DEBITADO_PARCIAL;
	}
}
