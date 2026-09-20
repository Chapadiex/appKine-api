package com.akine.person.domain.exception;

/**
 * No queda saldo en la autorizacion para el movimiento pedido (409).
 *
 * <p><b>Cero filas afectadas es lo que la produce</b>, no un {@code if} sobre un saldo leido: el
 * {@code UPDATE} condicional de {@code ConsumoDeAutorizacionService} lleva
 * {@code WHERE cantidad_autorizada - cantidad_consumida >= :cantidad}, y quien decide que no
 * alcanza es la base. Entre leer el saldo y escribirlo hay una ventana en la que otra transaccion
 * se lleva la ultima unidad; la condicion en el WHERE no tiene esa ventana. Es el patron con el
 * que 07.02 imputa un cobro.
 *
 * <p><b>El cierre de una sesion NUNCA la ve.</b> El observador de {@code person} no lanza: la
 * atencion ocurrio, y bloquear el cierre de una historia clinica porque el financiador se quedo
 * sin saldo es exactamente lo que DP-06 prohibe. Esta excepcion sale por los caminos que SI tiene
 * a alguien a quien avisarle — el ajuste administrativo y la reversion.
 */
public class AutorizacionSinSaldoException extends RuntimeException {

	private final long autorizacionId;
	private final int cantidadPedida;

	public AutorizacionSinSaldoException(long autorizacionId, int cantidadPedida) {
		super("La autorizacion no tiene saldo para " + cantidadPedida + " unidad(es)");
		this.autorizacionId = autorizacionId;
		this.cantidadPedida = cantidadPedida;
	}

	public long getAutorizacionId() {
		return autorizacionId;
	}

	public int getCantidadPedida() {
		return cantidadPedida;
	}
}
