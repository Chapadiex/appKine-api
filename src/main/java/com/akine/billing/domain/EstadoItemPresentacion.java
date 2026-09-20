package com.akine.billing.domain;

/**
 * Que le paso a una prestacion dentro del lote.
 *
 * <h2>Dos ocupan y dos liberan</h2>
 *
 * <p>RN-M21-003 —"una prestacion no debe duplicarse en presentaciones incompatibles"— no se cumple
 * con un {@code if}: la hace cumplir la columna <b>generada</b> {@code ocupa_marca} de V56, que
 * vale 1 para {@link #INCLUIDO} y {@link #ACEPTADO} y NULL para los otros dos, mas el unique
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
	 * "reapertura" y el "rechazo parcial" de la etapa.
	 */
	DEBITADO,

	/** El borrador que lo contenia se descarto. <b>Libera</b> la obligacion. */
	ANULADO;

	/** Si esta fila mantiene tomada su obligacion. Espeja {@code ocupa_marca} de V56. */
	public boolean ocupa() {
		return this == INCLUIDO || this == ACEPTADO;
	}
}
