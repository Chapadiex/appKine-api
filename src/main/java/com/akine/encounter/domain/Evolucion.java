package com.akine.encounter.domain;

/**
 * Como viene el paciente respecto de la sesion anterior.
 *
 * <p>Es la mitad "cambio" del requisito de la etapa, y viaja como enum y no como texto libre
 * justamente para que se pueda <b>consultar</b>: una evolucion clinica que no se puede comparar
 * entre sesiones no sirve para nada.
 *
 * <p>{@link #SIN_REFERENCIA} es el de la primera sesion de un tratamiento, y no un descuido: decir
 * "igual" cuando no hay contra que comparar seria inventar un dato clinico.
 */
public enum Evolucion {
	MEJOR,
	IGUAL,
	PEOR,
	SIN_REFERENCIA
}
