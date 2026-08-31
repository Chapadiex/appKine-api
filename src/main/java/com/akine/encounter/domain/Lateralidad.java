package com.akine.encounter.domain;

/**
 * De que lado.
 *
 * <p>{@link #NO_APLICA} existe y no es lo mismo que dejarlo vacio: una zona central —lumbar,
 * cervical— no tiene lado, y decirlo explicitamente distingue "no corresponde" de "no lo cargue".
 * Sin ese valor, la pantalla no puede saber si preguntar de nuevo.
 */
public enum Lateralidad {
	IZQUIERDA,
	DERECHA,
	BILATERAL,
	NO_APLICA
}
