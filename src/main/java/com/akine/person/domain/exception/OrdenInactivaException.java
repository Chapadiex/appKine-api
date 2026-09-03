package com.akine.person.domain.exception;

/**
 * La orden medica esta dada de baja y no admite la operacion (409).
 *
 * <p>Dos {@code problemType} distintos salen de esta misma excepcion: "ya estaba dada de baja" y
 * "no se puede editar porque esta de baja" son dos acciones distintas para quien las recibe. Lo
 * decide {@code PersonProblemHandler} mirando {@link #getOperacion()}.
 *
 * <p><b>Vencida no es dada de baja.</b> Una orden vencida sigue siendo operable: se la puede
 * editar para corregir la fecha, y sigue explicando con que papel se atendio al paciente.
 */
public class OrdenInactivaException extends RuntimeException {

	private final long ordenId;
	private final String operacion;

	public OrdenInactivaException(long ordenId, String operacion) {
		super("La orden medica esta dada de baja y no admite " + operacion);
		this.ordenId = ordenId;
		this.operacion = operacion;
	}

	public long getOrdenId() {
		return ordenId;
	}

	public String getOperacion() {
		return operacion;
	}
}
