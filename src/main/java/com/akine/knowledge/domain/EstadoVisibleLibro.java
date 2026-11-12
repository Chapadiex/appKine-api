package com.akine.knowledge.domain;

/** Estado que ve el usuario: el persistido, o INACTIVO si el libro esta dado de baja. */
public enum EstadoVisibleLibro {
	PENDIENTE, PROCESANDO, LISTO, ERROR, INACTIVO
}
