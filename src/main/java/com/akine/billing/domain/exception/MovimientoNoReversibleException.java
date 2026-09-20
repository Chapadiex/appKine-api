package com.akine.billing.domain.exception;

/**
 * Ese movimiento no admite reversion. <b>409</b>. Lleva {@code motivo}.
 *
 * <p>Dos causas, y las dos son la misma idea mirada de dos lados:
 *
 * <ul>
 *   <li><b>Ya fue revertido.</b> Lo hace cumplir el unique
 *       {@code (organization_id, tipo, tipo_origen, referencia_origen, medio)}; el servicio
 *       consulta antes para poder explicarlo en vez de devolver un error de constraint.</li>
 *   <li><b>Es una reversion.</b> Una reversion no se revierte: encadenarlas produce un historial
 *       que nadie puede leer. Para deshacerla se asienta un movimiento nuevo, que es honesto sobre
 *       lo que paso.</li>
 * </ul>
 *
 * <p>Un solo tipo para las dos, con {@code motivo} como propiedad extra: para la pantalla el
 * desenlace es el mismo. Mismo criterio que {@code ARCHIVO_NO_ACEPTADO} en 03.02.
 */
public class MovimientoNoReversibleException extends RuntimeException {

	private final long movimientoId;
	private final String motivo;

	public MovimientoNoReversibleException(long movimientoId, String motivo) {
		super("El movimiento de caja " + movimientoId + " no se puede revertir: " + motivo);
		this.movimientoId = movimientoId;
		this.motivo = motivo;
	}

	public long getMovimientoId() {
		return movimientoId;
	}

	public String getMotivo() {
		return motivo;
	}
}
