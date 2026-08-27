package com.akine.offering.domain.exception;

/**
 * La oferta no existe, o es de otro tenant, o es de otra sede del mismo tenant (404).
 *
 * <p><b>Los tres casos se responden igual, a proposito</b> (ADR-0018). Distinguirlos permitiria
 * recorrer ids consecutivos y averiguar que ofrece cada centro del SaaS y a que precio — que es
 * informacion comercial de ese centro y de nadie mas. Para quien no tiene acceso, la fila no
 * existe.
 *
 * <p><b>Este 404 NO es el mismo que {@link ServicioNotAccessibleException}.</b> Alli 404 significa
 * literalmente "no existe", porque el catalogo de Servicios es global y no hay ninguna fila ajena
 * que ocultar (ADR-0023). Aca 404 encubre ademas "existe y es de otro". Es la misma diferencia que
 * separa a {@code CatalogoNotAccessibleException} de las lecturas globales en {@code resource}.
 *
 * <p>Ojo con lo que esto NO cubre: una oferta dada de baja de la PROPIA sede <b>si</b> se devuelve,
 * con 200. RN-M27-007 exige que la baja logica conserve la historia, y responder 404 sobre una
 * oferta inactiva seria borrarla por la puerta de atras — ademas de dejar a la pantalla sin poder
 * explicar por que ese nombre comercial no se puede reusar.
 */
public class OfertaNotAccessibleException extends RuntimeException {

	private final long ofertaId;

	public OfertaNotAccessibleException(long ofertaId) {
		super("Oferta no accesible: " + ofertaId);
		this.ofertaId = ofertaId;
	}

	public long getOfertaId() {
		return ofertaId;
	}
}
