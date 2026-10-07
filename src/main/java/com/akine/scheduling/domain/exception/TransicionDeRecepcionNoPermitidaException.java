package com.akine.scheduling.domain.exception;

/**
 * La recepcion del turno no admite la operacion en su estado actual (409,
 * {@code recepcion-transicion-no-permitida}).
 *
 * <p>Tambien cubre "no hay ninguna recepcion abierta": validar, llamar o anular algo que no existe
 * es la misma clase de error para la pantalla —refrescar y mirar en que estado esta— y un 404
 * confundiria "el turno no existe" con "el turno existe y nadie llego".
 */
public class TransicionDeRecepcionNoPermitidaException extends RuntimeException {

	private final long turnoId;
	private final String motivo;

	public TransicionDeRecepcionNoPermitidaException(long turnoId, String motivo) {
		super("La recepcion del turno " + turnoId + " no admite la operacion: " + motivo);
		this.turnoId = turnoId;
		this.motivo = motivo;
	}

	public long getTurnoId() {
		return turnoId;
	}

	public String getMotivo() {
		return motivo;
	}
}
