package com.akine.clinical.domain.exception;

/**
 * La oferta que se quiere asociar al caso no existe en la sede, o no esta vigente (409).
 *
 * <p>RN-M10-006: la necesidad de Caso la determina la <b>Oferta efectiva</b>, asi que un caso que
 * apunta a una oferta que no puede prestarse no tiene sobre que apoyarse. Se valida contra
 * {@code offering.spi.OfertaDirectory} en el alta.
 *
 * <p><b>409 y no 404</b>, mismo criterio que {@code OFERTA_NO_AGENDABLE} en M12: la oferta existe y
 * quien la eligio la esta viendo en una lista. Un 404 mandaria a la pantalla a decir "no
 * encontrada" sobre algo que el usuario tiene delante; lo que corresponde es ofrecerle reactivarla
 * o elegir otra.
 *
 * <p>Junta a proposito "no existe en esta sede", "es de otro tenant" y "esta dada de baja": no es
 * el mismo criterio de privacidad que rige a la historia clinica —una oferta es catalogo comercial
 * del propio centro— pero distinguirlas no habilita ninguna accion distinta.
 */
public class OfertaNoVigenteException extends RuntimeException {

	private final long ofertaId;

	public OfertaNoVigenteException(long ofertaId) {
		super("La oferta " + ofertaId + " no esta vigente en esta sede");
		this.ofertaId = ofertaId;
	}

	public long getOfertaId() {
		return ofertaId;
	}
}
