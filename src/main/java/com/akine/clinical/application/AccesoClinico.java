package com.akine.clinical.application;

import com.akine.organization.spi.PermissionDecision;

/**
 * El resultado de evaluar un acceso clinico: por que se concedio, no solo que se concedio.
 *
 * <p>Existe porque DP-03 no pide una respuesta binaria. Pide que todo acceso clinico sensible
 * quede auditado, y una auditoria que solo diga "se leyo la historia 42" no sirve para revisar
 * nada: lo que hace falta saber es si quien leyo <b>atendia</b> a ese paciente o si entro por una
 * justificacion declarada, que es el caso que despues alguien tiene que revisar a mano.
 *
 * @param conRelacionAsistencial {@code true} si la sonda encontro evidencia de que el actor
 *                               atiende a esa persona. <b>Hoy es siempre {@code false}</b> porque
 *                               no existen turnos ni sesiones: ver
 *                               {@code clinical.spi.RelacionAsistencialProbe}
 * @param justificacion          motivo declarado por el actor, o {@code null} si no hizo falta.
 *                               Nunca vacio: la politica lo normaliza antes de construir esto
 */
record AccesoClinico(
		PermissionDecision decision, boolean conRelacionAsistencial, String justificacion) {

	/** Como se entro, en una palabra, para que el evento de auditoria lo diga sin adivinanzas. */
	String via() {
		return conRelacionAsistencial ? "RELACION_ASISTENCIAL" : "JUSTIFICACION";
	}

	/** {@code true} si quien accede es un administrador de plataforma bajo acceso de soporte. */
	boolean viaSupportAccess() {
		return decision.viaSupportAccess();
	}
}
