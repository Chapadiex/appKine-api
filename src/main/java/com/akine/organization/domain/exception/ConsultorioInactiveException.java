package com.akine.organization.domain.exception;

/**
 * Se intento una operacion que la sede, por estar INACTIVA, no admite (RN-M03-003).
 *
 * <p><b>409 y no 403:</b> el actor tiene el permiso; lo que no admite la operacion es el estado
 * del recurso. Y <b>409 y no 404:</b> la sede existe y es del tenant del actor, que ademas la
 * ve en su propio listado; responderle "no existe" lo dejaria sin entender que paso.
 *
 * <p>Cubre dos situaciones con el mismo codigo HTTP y distinto {@code type}, porque el frontend
 * ramifica por ahi y los mensajes son distintos:
 * <ul>
 *   <li>editar una sede inactiva &rarr; {@code consultorio-inactive};</li>
 *   <li>dar de baja una sede ya inactiva &rarr; {@code consultorio-already-inactive}.</li>
 * </ul>
 */
public class ConsultorioInactiveException extends RuntimeException {

	/** Que se intento hacer sobre la sede inactiva. Decide el {@code type} del Problem Details. */
	public enum Operacion {

		/** Edicion de datos, zona o intervalo (RF-M03-003). */
		EDICION,

		/** Segunda baja de una sede que ya estaba dada de baja (RF-M03-004). */
		BAJA
	}

	private final Long consultorioId;
	private final Operacion operacion;

	public ConsultorioInactiveException(Long consultorioId, Operacion operacion) {
		super("La sede esta dada de baja y no admite esta operacion");
		this.consultorioId = consultorioId;
		this.operacion = operacion;
	}

	public Long getConsultorioId() {
		return consultorioId;
	}

	public Operacion getOperacion() {
		return operacion;
	}
}
