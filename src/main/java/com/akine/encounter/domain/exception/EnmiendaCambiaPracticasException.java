package com.akine.encounter.domain.exception;

import java.util.Set;

/**
 * Una enmienda cambiaria el conjunto de practicas realizadas en la sesion. <b>409</b> (C-6).
 *
 * <p>Ese conjunto es lo que {@code SesionCerrada.practicasRealizadas()} llevo al cierre, y con el
 * {@code person} eligio <b>que autorizacion consumir</b> (06.04). Una enmienda no vuelve a
 * disparar los observadores del cierre (06.06 §5), asi que cambiar la practica despues dejaria
 * consumida la autorizacion de una prestacion que la historia clinica ya no registra. Corregirlo
 * es una compensacion economica explicita en M17, no una enmienda clinica.
 */
public class EnmiendaCambiaPracticasException extends RuntimeException {

	private final Long sesionId;

	public EnmiendaCambiaPracticasException(Long sesionId, Set<Long> antes, Set<Long> despues) {
		super("La enmienda de la sesion " + sesionId + " cambiaria las practicas realizadas ("
				+ antes + " -> " + despues + "): cambiar que se presto mueve el consumo de "
				+ "autorizaciones y no se corrige enmendando");
		this.sesionId = sesionId;
	}

	public Long getSesionId() {
		return sesionId;
	}
}
