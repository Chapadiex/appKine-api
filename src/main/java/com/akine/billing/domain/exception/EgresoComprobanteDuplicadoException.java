package com.akine.billing.domain.exception;

/**
 * Ese comprobante de ese beneficiario ya esta cargado y vigente. <b>409</b>.
 *
 * <p>Es el caso borde "factura externa duplicada". Lo respalda
 * {@code uk_egreso_comprobante (organization_id, beneficiario_clave, comprobante_tipo,
 * comprobante_numero, anulado_key)}; esta excepcion existe para poder explicarlo con un 409
 * legible en vez de dejar que reviente una constraint — que ademas dejaria la transaccion marcada
 * para rollback y haria fallar cualquier consulta posterior.
 *
 * <p>La clave del beneficiario entra en el unique porque dos proveedores distintos emiten
 * legitimamente su propia {@code FACTURA_B 0001-00000123}.
 */
public class EgresoComprobanteDuplicadoException extends RuntimeException {

	private final String comprobanteTipo;
	private final String comprobanteNumero;
	private final Long egresoExistenteId;

	public EgresoComprobanteDuplicadoException(
			String comprobanteTipo, String comprobanteNumero, Long egresoExistenteId) {

		super("El comprobante " + comprobanteTipo + " " + comprobanteNumero
				+ " de este beneficiario ya esta cargado en el egreso " + egresoExistenteId);
		this.comprobanteTipo = comprobanteTipo;
		this.comprobanteNumero = comprobanteNumero;
		this.egresoExistenteId = egresoExistenteId;
	}

	public String getComprobanteTipo() {
		return comprobanteTipo;
	}

	public String getComprobanteNumero() {
		return comprobanteNumero;
	}

	public Long getEgresoExistenteId() {
		return egresoExistenteId;
	}
}
