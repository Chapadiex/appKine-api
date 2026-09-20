package com.akine.encounter.domain.exception;

/**
 * El espacio declarado no estaba operable cuando ocurrio la atencion. <b>409.</b>
 *
 * <p>Es 409 y no 404 porque el espacio <b>existe y es del tenant</b>: lo que falla es su estado.
 * Un espacio de otro tenant o de otra sede es {@code EspacioNoAccesible} y responde 404, por el
 * mismo motivo de siempre —un 403 o un 409 confirmarian el id—.
 *
 * <p><b>La vigencia se evalua en el instante de la ATENCION, no en el de la carga.</b> Evaluarla
 * "ahora" haria que registrar el viernes una atencion del lunes fallara porque el box se dio de
 * baja el miercoles, y eso es negar un hecho que ocurrio.
 *
 * <p><b>Lo que esta excepcion NO expresa es ocupacion ni capacidad</b>, y eso es deliberado: un
 * tratamiento realizado es un hecho consumado, y rechazarlo por ocupacion no impide la
 * sobreocupacion —ya paso— sino que impide documentarla. La ocupacion es regla de RESERVA y su
 * lugar es 05.02.
 */
public class EspacioNoOperableException extends RuntimeException {

	private final long espacioId;

	public EspacioNoOperableException(long espacioId) {
		super("El espacio " + espacioId + " no estaba operable en el momento de la atencion");
		this.espacioId = espacioId;
	}

	public long getEspacioId() {
		return espacioId;
	}
}
