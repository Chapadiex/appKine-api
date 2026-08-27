package com.akine.offering.domain;

/**
 * Clasificacion del {@link Servicio} (RF-M06-006).
 *
 * <h2>Que es y, sobre todo, que NO es</h2>
 *
 * <p><b>Sirve para clasificar, nunca para decidir.</b> RN-M06-005 lo dice en su propia letra:
 * la naturaleza "sirve para clasificacion y no debe imponer por si sola comportamiento clinico".
 * Que un servicio sea {@code CLINICO} no abre un Caso Clinico ni genera un registro: eso lo
 * deciden {@code requiereCasoClinico} y {@code generaRegistroClinico}, que viven en la
 * {@link OfertaServicioConsultorio} y no en este enum. Un {@code if (naturaleza == CLINICO)} que
 * dispare comportamiento clinico es exactamente la violacion que RN-M06-005 prohibe, y no hay
 * ningun lugar de este modulo que lo necesite: los campos {@code requiere_*} ya cargan esa
 * decision de forma explicita.
 *
 * <p>Los valores replican exactamente {@code ck_servicio_naturaleza} de la migracion V24. Agregar
 * uno exige migracion, y eso es deliberado: el conjunto es del producto y no del tenant, mismo
 * criterio que {@code EspacioTipo} (V19).
 */
public enum Naturaleza {

	/** Practica de rehabilitacion o tratamiento con intervencion de un profesional de la salud. */
	CLINICO,

	/** Actividad de recuperacion o mantenimiento funcional sin ser, en si misma, un tratamiento. */
	TERAPEUTICO,

	/** Actividad orientada a evitar una lesion o una recaida antes de que ocurra. */
	PREVENTIVO,

	/** Actividad de acondicionamiento o mantenimiento general, sin finalidad clinica declarada. */
	BIENESTAR
}
