package com.akine.billing.domain.exception;

/**
 * Esa prestacion ya esta viva en otro lote. <b>409</b>. RN-M21-003.
 *
 * <p>Una obligacion puede aparecer en <b>varias</b> presentaciones a lo largo del tiempo —se
 * presenta, la debitan, se corrige y se vuelve a presentar— pero <b>en una sola a la vez</b>.
 *
 * <p>No lo decide un {@code if}: lo decide el unique
 * {@code (organization_id, obligacion_id, ocupa_marca)} sobre una columna <b>generada</b> a partir
 * del estado del item. El servicio consulta antes para poder responder esto con el lote que la
 * tiene —que es lo que permite que el administrativo vaya a mirarlo— en vez de dejar reventar una
 * constraint, que ademas dejaria la transaccion marcada para rollback.
 */
public class ObligacionYaPresentadaException extends RuntimeException {

	private final long obligacionId;
	private final long presentacionId;

	public ObligacionYaPresentadaException(long obligacionId, long presentacionId) {
		super("La obligacion " + obligacionId + " ya esta incluida en la presentacion "
				+ presentacionId);
		this.obligacionId = obligacionId;
		this.presentacionId = presentacionId;
	}

	public long getObligacionId() {
		return obligacionId;
	}

	public long getPresentacionId() {
		return presentacionId;
	}
}
