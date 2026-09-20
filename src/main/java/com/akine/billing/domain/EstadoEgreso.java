package com.akine.billing.domain;

/**
 * En que punto de su vida esta el compromiso.
 *
 * <h2>El borrador no es decoracion de CRUD</h2>
 *
 * <p>Es la unica fase donde el egreso se puede editar, y existe porque la liquidacion de un
 * profesional se arma mirando papeles: se carga el importe, se busca la factura, se corrige el
 * periodo. Sin borrador, cada correccion seria una anulacion mas un alta nueva y el historico se
 * llenaria de anulaciones <b>que no son errores sino tipeo</b>.
 */
public enum EstadoEgreso {

	/** Editable. No admite pagos y no afecta nada. */
	BORRADOR,

	/**
	 * Punto de no retorno: no se edita mas, y recien ahora admite pagos.
	 *
	 * <p>El beneficiario, el importe y el comprobante quedan congelados.
	 */
	CONFIRMADO,

	/** Saldo cero por pagos asentados. Distinto de ANULADO: aca la plata salio. */
	PAGADO,

	/** Anulado con motivo. No se borra: RN-M22-002, "anular no significa borrar". */
	ANULADO;

	/** Solo un egreso confirmado puede mover la caja, y solo mientras le quede saldo. */
	public boolean admitePago() {
		return this == CONFIRMADO;
	}

	public boolean esEditable() {
		return this == BORRADOR;
	}
}
