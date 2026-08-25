package com.akine.resource.domain.exception;

/**
 * Ya hay un espacio VIGENTE con ese nombre en esa sede (409).
 *
 * <p>La garantia real no es una consulta previa sino el unique
 * {@code uk_espacio_sede_name_vigente}: entre un SELECT de comprobacion y el INSERT hay una
 * ventana en la que otro request entra. Esta excepcion se lanza traduciendo la violacion del
 * unique, que es la unica comprobacion que no tiene ventana.
 *
 * <p>El nombre de una sede vecina no colisiona, y el de un espacio dado de baja tampoco: el
 * unique lleva el discriminador de baja. Ver la cabecera de la migracion V19.
 */
public class EspacioNameTakenException extends RuntimeException {

	private final String name;

	public EspacioNameTakenException(String name) {
		super("Ya existe un espacio vigente con ese nombre en la sede");
		this.name = name;
	}

	public String getName() {
		return name;
	}
}
