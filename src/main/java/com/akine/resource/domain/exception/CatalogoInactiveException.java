package com.akine.resource.domain.exception;

/**
 * Se intento mutar un concepto de catalogo dado de baja (409).
 *
 * <p>409 y no 404: el actor tiene el permiso y el recurso esta en su alcance; lo que no admite
 * la operacion es el ESTADO. Y no 404 porque el concepto se sigue leyendo con 200 —RN-M06-001 y
 * RN-M06-002— y responder que no existe justo cuando se lo quiere editar contradiria la lectura
 * que acaba de devolverlo.
 *
 * <p>La operacion viaja en la excepcion porque los tres casos se le cuentan distinto al
 * usuario, y el contrato les da {@code type} distintos para que el frontend no tenga que leer
 * prosa.
 */
public class CatalogoInactiveException extends RuntimeException {

	/** Que se intento hacer sobre el concepto inactivo. */
	public enum Operacion {

		/** Editar nombre, descripcion o vigencia. */
		EDICION,

		/** Darlo de baja por segunda vez. */
		BAJA,

		/** Colgarle algo: una practica de una especialidad, una vigencia de un nomenclador. */
		REFERENCIA
	}

	private final long conceptoId;
	private final Operacion operacion;

	public CatalogoInactiveException(long conceptoId, Operacion operacion) {
		super("El concepto de catalogo " + conceptoId + " esta dado de baja");
		this.conceptoId = conceptoId;
		this.operacion = operacion;
	}

	public long getConceptoId() {
		return conceptoId;
	}

	public Operacion getOperacion() {
		return operacion;
	}
}
