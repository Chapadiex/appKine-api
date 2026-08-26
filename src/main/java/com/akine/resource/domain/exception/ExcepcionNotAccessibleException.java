package com.akine.resource.domain.exception;

/**
 * La excepcion de disponibilidad de la ruta no existe, es de otra sede o es de otro tenant (404).
 *
 * <p>Los tres casos se responden igual, por la regla heredada de 01.01: distinguirlos permitiria
 * recorrer ids consecutivos y contar los cierres de cada centro del SaaS.
 *
 * <p><b>Nota para la tarea 10:</b> esta excepcion NO figura en la lista de archivos del brief de
 * la tarea 8. Se agrego porque sin ella la baja de una excepcion inexistente tendria que salir
 * por el 404 de otra entidad —y la fila del log mentiria sobre que fue lo que no resolvio— o por
 * un {@code IllegalStateException} que el advice traduciria a 500. El advice del modulo tiene que
 * mapearla a 404.
 */
public class ExcepcionNotAccessibleException extends RuntimeException {

	private final long excepcionId;

	public ExcepcionNotAccessibleException(long excepcionId) {
		super("Excepcion de disponibilidad no accesible: " + excepcionId);
		this.excepcionId = excepcionId;
	}

	public long getExcepcionId() {
		return excepcionId;
	}
}
