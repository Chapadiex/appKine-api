package com.akine.resource.domain.exception;

/**
 * Ya existe un concepto VIGENTE con ese nombre para el mismo duenio (409).
 *
 * <p>Es la validacion de "duplicados normalizados" de la etapa. La normalizacion no la hace un
 * campo calculado: la hace la <b>collation</b> {@code utf8mb4_0900_ai_ci} de la tabla, que es
 * insensible a mayusculas y a acentos, mas un {@code strip} de espacios en la aplicacion. Por
 * eso dos grafias del mismo nombre chocan sin que exista ninguna columna extra que mantener
 * sincronizada, y sin una segunda definicion de "igual" que tarde o temprano diverge.
 *
 * <p>Como el de codigo: un concepto dado de baja libera su nombre.
 */
public class CatalogoNameTakenException extends RuntimeException {

	private final String name;
	private final boolean global;

	public CatalogoNameTakenException(String name, boolean global) {
		super("Nombre de catalogo en uso");
		this.name = name;
		this.global = global;
	}

	public String getName() {
		return name;
	}

	public boolean isGlobal() {
		return global;
	}
}
