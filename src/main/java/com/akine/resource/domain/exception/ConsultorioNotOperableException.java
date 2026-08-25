package com.akine.resource.domain.exception;

/**
 * Se intento crear un espacio en una sede dada de baja (409).
 *
 * <p>Es RN-M03-003 aplicada un nivel mas abajo: una sede inactiva no origina hechos nuevos, y
 * un box nuevo es un hecho nuevo. Lo que la sede inactiva SI sigue haciendo es responder por
 * los espacios que ya tenia, con 200: darlos por inexistentes borraria historia.
 *
 * <p>409 y no 404 porque el actor puede leer esa sede perfectamente; lo que no admite la
 * operacion es su estado. Y no 403 porque el permiso lo tiene.
 *
 * <p>Se traduce al {@code type} {@code consultorio-inactive}, que ya existe en el catalogo
 * desde 02.01: es el mismo hecho contado desde otro modulo, y darle un codigo nuevo obligaria
 * al frontend a manejar dos valores para el mismo mensaje.
 */
public class ConsultorioNotOperableException extends RuntimeException {

	private final long consultorioId;

	public ConsultorioNotOperableException(long consultorioId) {
		super("La sede " + consultorioId + " esta dada de baja y no admite espacios nuevos");
		this.consultorioId = consultorioId;
	}

	public long getConsultorioId() {
		return consultorioId;
	}
}
