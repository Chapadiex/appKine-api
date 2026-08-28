package com.akine.person.domain.exception;

/**
 * Ya hay una {@code Persona} VIGENTE con ese documento en la organizacion (409).
 *
 * <p>Es el invariante duro de RN-M07-001 y el que hace innecesaria toda deteccion de duplicados
 * "entre sedes": con el unique {@code uk_persona_documento_vigente} a nivel organizacion, el
 * duplicado por documento <b>no se puede crear</b>, en ninguna sede.
 *
 * <p>Traduce la violacion del unique y no un SELECT previo, por la ventana de carrera de siempre:
 * entre la comprobacion y el INSERT entra otro request. El documento de una persona dada de baja
 * si se puede reusar — el unique lleva {@code deleted_key} como discriminador.
 *
 * <h2>Por que lleva el {@code personaId} de la fila que ya existe</h2>
 *
 * <p>Porque sin el, el operador del mostrador queda en un callejon: el sistema le dice "ya existe"
 * y no le dice cual, asi que tiene que salir de la pantalla, buscar a mano y volver. Con el id, la
 * pantalla ofrece "abrir la ficha existente", que es la accion que RN-M07-001 quiere que ocurra.
 *
 * <p><b>Y no filtra nada</b>, que es la pregunta obvia: quien recibe este 409 acaba de demostrar
 * que conoce el documento de esa persona y tiene permiso para dar de alta en esa organizacion. El
 * id que se entrega es de una fila que ese mismo actor puede leer con {@code GET /personas/{id}}.
 */
public class PersonaDocumentoTakenException extends RuntimeException {

	private final Long personaExistenteId;

	public PersonaDocumentoTakenException(Long personaExistenteId) {
		super("Ya existe una persona vigente con ese documento en la organizacion");
		this.personaExistenteId = personaExistenteId;
	}

	/** Id de la persona que ya tiene ese documento, o {@code null} si no se pudo determinar. */
	public Long getPersonaExistenteId() {
		return personaExistenteId;
	}
}
