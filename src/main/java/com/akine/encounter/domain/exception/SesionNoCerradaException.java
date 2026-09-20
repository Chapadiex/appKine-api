package com.akine.encounter.domain.exception;

/**
 * Se intento enmendar una sesion que todavia esta abierta. <b>409</b>.
 *
 * <p>Es el espejo exacto de {@link SesionCerradaException}, y tener las dos no es simetria
 * decorativa: son dos situaciones que llevan a <b>acciones distintas</b>, y un unico
 * {@code conflict} obligaria a la pantalla a adivinar cual. Si la sesion esta cerrada, lo que
 * corresponde ofrecer es enmendar; si esta abierta, lo que corresponde es guardar normalmente
 * —evaluacion o borrador—, que ademas no exige motivo ni deja una version en el historial.
 *
 * <p>Enmendar una sesion abierta no solo es innecesario: <b>produciria una version 2 de algo que
 * todavia esta cambiando</b>, y el historial clinico pasaria a registrar tipeos en vez de
 * correcciones. Por eso la version 1 se escribe al cerrar y no antes.
 */
public class SesionNoCerradaException extends RuntimeException {

	private final Long sesionId;

	public SesionNoCerradaException(Long sesionId) {
		super("La sesion " + sesionId + " todavia esta abierta: se guarda, no se enmienda");
		this.sesionId = sesionId;
	}

	public Long getSesionId() {
		return sesionId;
	}
}
