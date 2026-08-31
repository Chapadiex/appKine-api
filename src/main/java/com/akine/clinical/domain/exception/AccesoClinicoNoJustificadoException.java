package com.akine.clinical.domain.exception;

/**
 * El actor tiene el permiso clinico pero no tiene con que justificar este acceso.
 *
 * <p>Es DP-03 completa: consultar una historia clinica exige membership vigente, permiso clinico
 * <b>y</b> relacion asistencial o justificacion autorizada. El permiso solo no alcanza — si
 * alcanzara, cualquier profesional del centro podria leer la historia de cualquier paciente sin
 * dejar mas rastro que un evento de lectura mas.
 *
 * <p>No es un 404: el actor esta autorizado a operar en ese tenant y la historia existe. Es la
 * tercera condicion la que falta, y el llamador la resuelve declarando el motivo — que es el caso
 * borde "acceso de emergencia autorizado" de la etapa.
 */
public class AccesoClinicoNoJustificadoException extends RuntimeException {

	private final long personaId;

	public AccesoClinicoNoJustificadoException(long personaId) {
		super("El acceso a la historia clinica de la persona " + personaId
				+ " exige relacion asistencial o una justificacion declarada");
		this.personaId = personaId;
	}

	public long getPersonaId() {
		return personaId;
	}
}
