package com.akine.clinical.domain.exception;

/**
 * El adjunto clinico no existe, es de otra organizacion, o es de otra historia (404).
 *
 * <p>Los tres casos colapsan en el mismo error a proposito, mismo criterio que
 * {@link HistoriaClinicaNotAccessibleException} y {@link EntradaClinicaNotAccessibleException}:
 * distinguirlos confirmaria que ese id existe en algun lado, y bastaria recorrer numeros para
 * medir cuantos estudios guarda la historia de otro paciente.
 *
 * <p>Tambien se usa cuando el pedido nombra una entrada clinica que <b>no es de esta
 * historia</b>. Podria ser un 400 —el dato es incoherente— y es 404 deliberadamente: un 400
 * distinguiria "esa entrada no existe" de "esa entrada existe pero es de otro paciente", que es
 * justamente lo que no se puede confirmar.
 */
public class AdjuntoClinicoNotAccessibleException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final long adjuntoId;

	public AdjuntoClinicoNotAccessibleException(long adjuntoId) {
		super("Adjunto clinico no accesible: " + adjuntoId);
		this.adjuntoId = adjuntoId;
	}

	public long getAdjuntoId() {
		return adjuntoId;
	}
}
