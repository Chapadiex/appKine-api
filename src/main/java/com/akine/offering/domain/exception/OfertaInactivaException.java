package com.akine.offering.domain.exception;

/**
 * Se intento EDITAR o DAR DE BAJA una {@code Oferta} que ya estaba dada de baja (409).
 *
 * <h2>Por que 409 y no 404</h2>
 *
 * <p>La oferta existe, es de esta sede y se lee perfectamente con 200: RN-M27-007 exige que la
 * baja logica conserve el historico —el nombre comercial, el precio y la duracion con los que se
 * presto— y un 404 aca borraria esa historia. Lo que no admite es una operacion NUEVA sobre ella.
 *
 * <h2>Por que la segunda baja no es idempotente</h2>
 *
 * <p><b>No hay reactivacion.</b> Una oferta que vuelve al catalogo de la sede es un alta nueva —el
 * unique {@code uk_oferta_sede_nombre_vigente} lleva {@code deleted_key}, asi que su nombre
 * comercial quedo libre—, no una baja deshecha. Modelarlo como baja deshecha borraria el rastro de
 * que la sede dejo de ofrecer eso alguna vez, que es justo lo que la baja logica existe para
 * conservar. Mismo criterio, y misma redaccion, que {@link ServicioYaInactivoException} para el
 * catalogo global y que {@code EspacioInactiveException} en 02.02.
 *
 * <p>{@link #getOperacion()} distingue los dos caminos que llegan aca porque el cliente no hace lo
 * mismo con cada uno: ante la edicion tiene que recargar la pantalla, y ante la baja no tiene nada
 * que hacer — el efecto que pedia ya esta.
 */
public class OfertaInactivaException extends RuntimeException {

	private final long ofertaId;

	private final String operacion;

	public OfertaInactivaException(long ofertaId, String operacion) {
		super("La oferta " + ofertaId + " esta dada de baja: no admite " + operacion);
		this.ofertaId = ofertaId;
		this.operacion = operacion;
	}

	public long getOfertaId() {
		return ofertaId;
	}

	/** Que se intento hacer: {@code editar} o {@code dar de baja}. Va al detalle del problema. */
	public String getOperacion() {
		return operacion;
	}
}
