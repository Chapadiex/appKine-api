package com.akine.scheduling.domain.exception;

/**
 * La oferta existe en el tenant pero no puede producir agenda en ninguna fecha: esta dada de baja
 * o su vigencia no toca la ventana pedida.
 *
 * <p><b>Es un 409 y no un 404.</b> La oferta existe y quien pregunta tiene derecho a verla; lo que
 * no se puede es agendar contra ella. Un 404 mentiria sobre su existencia y mandaria a la pantalla
 * a decir "no encontrada" cuando el administrador la esta viendo en la lista.
 *
 * <p>Distinto del dia suelto que sale con {@code OFERTA_NO_VIGENTE}: ese es un dia de una ventana
 * que por lo demas si produce agenda. Esto es la ventana entera.
 */
public class OfertaNoAgendableException extends RuntimeException {

	private final long ofertaId;

	public OfertaNoAgendableException(long ofertaId, String motivo) {
		super("La oferta " + ofertaId + " no puede agendarse: " + motivo);
		this.ofertaId = ofertaId;
	}

	public long getOfertaId() {
		return ofertaId;
	}
}
