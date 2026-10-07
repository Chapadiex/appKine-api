package com.akine.billing.domain.exception;

/**
 * El turno ya tiene un prepago vigente (AKINE E-6). <b>409</b>, con el {@code cobroId} existente.
 *
 * <p>Es el desenlace de dos operadores que cobran el mismo prepago, o de un reintento sin clave
 * de idempotencia. Lo sostiene la base con {@code uk_cobro_prepago_turno_vigente}; esta excepcion
 * es la respuesta amable del caso comun. Para cobrar otro, primero se anula el vigente.
 */
public class PrepagoYaRegistradoException extends RuntimeException {

	private final long turnoId;
	private final Long cobroId;

	public PrepagoYaRegistradoException(long turnoId, Long cobroId) {
		super("El turno " + turnoId + " ya tiene un prepago vigente"
				+ (cobroId == null ? "" : ": el cobro " + cobroId));
		this.turnoId = turnoId;
		this.cobroId = cobroId;
	}

	public long getTurnoId() {
		return turnoId;
	}

	public Long getCobroId() {
		return cobroId;
	}
}
