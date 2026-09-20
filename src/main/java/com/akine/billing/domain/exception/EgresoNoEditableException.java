package com.akine.billing.domain.exception;

/**
 * Se intenta editar un egreso que ya no es borrador. <b>409</b>.
 *
 * <p>Confirmar congela beneficiario, importe y comprobante. Un importe que cambiara debajo de
 * pagos ya asentados haria que el saldo dejara de reconciliar con el ledger de caja <b>sin que
 * nada fallara</b>, que es la peor forma de romperse.
 */
public class EgresoNoEditableException extends RuntimeException {

	private final Long egresoId;
	private final String estado;

	public EgresoNoEditableException(Long egresoId, String estado) {
		super("El egreso " + egresoId + " esta " + estado + " y ya no se edita");
		this.egresoId = egresoId;
		this.estado = estado;
	}

	public Long getEgresoId() {
		return egresoId;
	}

	public String getEstado() {
		return estado;
	}
}
