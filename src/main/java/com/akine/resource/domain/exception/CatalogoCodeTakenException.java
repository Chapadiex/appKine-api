package com.akine.resource.domain.exception;

/**
 * Ya existe un concepto VIGENTE con ese codigo para el mismo duenio (409).
 *
 * <p>409 y no 400: el codigo no es invalido, esta ocupado. Y el conflicto es contra otra fila
 * que el actor ya puede ver en su propio listado, asi que decirlo no revela nada nuevo.
 *
 * <p><b>Un concepto dado de baja NO produce este conflicto:</b> su codigo queda libre. Es lo
 * que hace posible reponer una practica despues de haberla discontinuado, y lo sostiene el
 * centinela {@code deleted_key} del unique de la migracion V20.
 *
 * <p>El duenio viaja en la excepcion para poder decir si el choque fue contra el catalogo
 * global o contra el propio: son dos acciones distintas para el usuario —pedirle a la
 * plataforma que lo revise, o editar el suyo—.
 */
public class CatalogoCodeTakenException extends RuntimeException {

	private final String codigo;
	private final boolean global;

	public CatalogoCodeTakenException(String codigo, boolean global) {
		super("Codigo de catalogo en uso: " + codigo);
		this.codigo = codigo;
		this.global = global;
	}

	public String getCodigo() {
		return codigo;
	}

	/** {@code true} si el conflicto fue dentro del catalogo de plataforma. */
	public boolean isGlobal() {
		return global;
	}
}
