package com.akine.clinical.domain;

/**
 * Con que rol participa un profesional en un Caso Clinico (RF-M10-005).
 *
 * <p>Son dos y no una jerarquia: <b>ninguno de los dos otorga ni quita permisos</b>. Quien puede
 * escribir en la historia lo decide {@code hc:write} y la membership vigente, no esta etiqueta.
 * Lo que expresa es quien responde por el caso cuando hay que preguntarle a alguien, que es un
 * dato clinico-organizativo y no de autorizacion.
 */
public enum RolEnCaso {

	/** Responde por el caso. La aplicacion no exige que haya exactamente uno: ver el equipo. */
	RESPONSABLE,

	/** Atiende dentro del caso. Es el rol por defecto. */
	TRATANTE
}
