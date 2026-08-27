package com.akine.offering.domain.exception;

/**
 * Se intento EDITAR o DAR DE BAJA un {@code Servicio} que ya estaba dado de baja (409).
 *
 * <h2>Por que no alcanzaba con {@link ServicioInactivoException}</h2>
 *
 * <p>Las dos son 409 y las dos dicen "el estado del servicio no admite esto", pero no son el
 * mismo hecho y el cliente no hace lo mismo con cada una:
 *
 * <pre>
 *   ServicioInactivoException     el servicio inactivo es el OBJETO de una operacion sobre OTRA
 *                                 entidad: crear una Oferta nueva sobre el (RF-M27-002). Quien
 *                                 la recibe tiene que elegir otro servicio.
 *   ServicioYaInactivoException   el servicio inactivo es el SUJETO de la operacion: se lo quiso
 *                                 editar o volver a dar de baja. Quien la recibe tiene una
 *                                 pantalla con datos viejos y lo que necesita es recargar.
 * </pre>
 *
 * <p>Fundirlas en una sola obligaria a leer el mensaje para saber cual de las dos cosas paso, que
 * es exactamente lo que un {@code type} distinto existe para evitar.
 *
 * <h2>Por que 409 y no 404, y por que no hay reactivacion</h2>
 *
 * <p>409 y no 404: el servicio existe, es global y es perfectamente legible con 200 —RN-M03-006
 * exige que los historicos conserven su nombre y su codigo—. Lo que no admite son operaciones
 * nuevas.
 *
 * <p><b>No hay reactivacion, y por eso una segunda baja no es idempotente sino un conflicto:</b>
 * un servicio que vuelve al catalogo es un alta nueva —el unique lleva {@code deleted_key}, asi
 * que su codigo quedo libre—, no una baja deshecha. Modelarlo como baja deshecha borraria el
 * rastro de que el servicio dejo de ofrecerse alguna vez, que es justo lo que la baja logica
 * existe para conservar.
 */
public class ServicioYaInactivoException extends RuntimeException {

	private final long servicioId;

	private final String operacion;

	public ServicioYaInactivoException(long servicioId, String operacion) {
		super("El servicio " + servicioId + " esta dado de baja: no admite " + operacion);
		this.servicioId = servicioId;
		this.operacion = operacion;
	}

	public long getServicioId() {
		return servicioId;
	}

	/** Que se intento hacer: {@code editar} o {@code dar de baja}. Va al detalle del problema. */
	public String getOperacion() {
		return operacion;
	}
}
