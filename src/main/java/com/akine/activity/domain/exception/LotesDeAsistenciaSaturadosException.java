package com.akine.activity.domain.exception;

/**
 * La sede ya tiene tantos lotes de asistencia en curso como permite
 * {@code akine.activity.asistencia.lotes-concurrentes-por-sede}, y este no consiguio lugar dentro
 * de la espera maxima (U-49 c, DP-30).
 *
 * <p>Se lanza <b>antes del primer item</b>: el lote rechazado no escribio nada y reintentarlo es
 * seguro. Viaja como 503 {@code service-unavailable} con {@code Retry-After}.
 */
public class LotesDeAsistenciaSaturadosException extends RuntimeException {

	private final long consultorioId;
	private final long segundosParaReintentar;

	public LotesDeAsistenciaSaturadosException(long consultorioId, long segundosParaReintentar) {
		super("La sede " + consultorioId + " no tiene lugar para otro lote de asistencia");
		this.consultorioId = consultorioId;
		this.segundosParaReintentar = segundosParaReintentar;
	}

	public long getConsultorioId() {
		return consultorioId;
	}

	public long getSegundosParaReintentar() {
		return segundosParaReintentar;
	}
}
