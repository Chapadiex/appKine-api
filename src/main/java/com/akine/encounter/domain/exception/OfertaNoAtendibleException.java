package com.akine.encounter.domain.exception;

/**
 * La oferta pedida para una atencion sin turno no habilita atenderla (U-24, DP-43).
 *
 * <p>La atencion de turno recibe la oferta ya validada por la reserva; la sin turno la recibe del
 * cliente y la valida aca. <b>404 o 409 segun el motivo</b>: una oferta que no existe en la sede
 * —o es de otro tenant— es 404 indistinguible; una que existe y no esta vigente hoy es 409
 * {@code oferta-no-vigente}, porque lleva a otra accion; y un profesional que no esta habilitado
 * para ella es 409 {@code profesional-no-habilitado-en-oferta}.
 */
public class OfertaNoAtendibleException extends RuntimeException {

	/** Por que la oferta no habilita la atencion. */
	public enum Motivo {
		NO_EXISTE,
		NO_VIGENTE,
		PROFESIONAL_NO_HABILITADO
	}

	private final long ofertaId;
	private final Motivo motivo;

	public OfertaNoAtendibleException(long ofertaId, Motivo motivo) {
		super(switch (motivo) {
			case NO_EXISTE -> "La oferta no existe.";
			case NO_VIGENTE -> "La oferta " + ofertaId + " no esta vigente hoy en esta sede.";
			case PROFESIONAL_NO_HABILITADO ->
					"El profesional no esta habilitado para la oferta " + ofertaId + ".";
		});
		this.ofertaId = ofertaId;
		this.motivo = motivo;
	}

	public long getOfertaId() {
		return ofertaId;
	}

	public Motivo getMotivo() {
		return motivo;
	}
}
