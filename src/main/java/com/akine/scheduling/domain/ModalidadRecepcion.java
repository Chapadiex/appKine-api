package com.akine.scheduling.domain;

/**
 * Como se resolvio administrativamente la atencion en la recepcion.
 *
 * <p>Es un snapshot de la recepcion y <b>no</b> cambia la cobertura maestra del paciente
 * (RN-M13-004): elegir Particular hoy no le borra la obra social a nadie.
 */
public enum ModalidadRecepcion {

	/** Con una cobertura aplicable y elegible. */
	COBERTURA,

	/** Sin cobertura: decision explicita del operador, con motivo (RF-M13-005). */
	PARTICULAR
}
