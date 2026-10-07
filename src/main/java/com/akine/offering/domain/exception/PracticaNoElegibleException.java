package com.akine.offering.domain.exception;

/**
 * Una practica que se pidio agregar a una oferta no se puede agregar (A-9).
 *
 * <p>Dos motivos con dos respuestas distintas, el mismo criterio que {@code encounter} en 06.04:
 *
 * <ul>
 *   <li>{@link Motivo#INEXISTENTE} — no existe o es de otro tenant: <b>404</b>, indistinguibles a
 *       proposito.</li>
 *   <li>{@link Motivo#NO_VIGENTE} — existe y el tenant la ve, pero hoy no se puede elegir: <b>409
 *       {@code practica-no-utilizable}</b>, porque lo que corresponde es elegir otra.</li>
 * </ul>
 *
 * <p>Solo se evalua para las practicas que ENTRAN. Las que ya estaban y siguen pedidas no se
 * revalidan: dar de baja una practica en M06 no rompe la configuracion de las ofertas que la usaban
 * (RN-M06-002).
 */
public class PracticaNoElegibleException extends RuntimeException {

	public enum Motivo { INEXISTENTE, NO_VIGENTE }

	private final long practicaId;
	private final Motivo motivo;

	public PracticaNoElegibleException(long practicaId, Motivo motivo) {
		super("Practica no elegible para la oferta: practicaId=" + practicaId + " motivo=" + motivo);
		this.practicaId = practicaId;
		this.motivo = motivo;
	}

	public long getPracticaId() {
		return practicaId;
	}

	public Motivo getMotivo() {
		return motivo;
	}
}
