package com.akine.organization.spi;

/**
 * Lo que el centro decidio sobre cada funcion de IA. No mira el despliegue: eso lo sigue
 * chequeando cada modulo con su propia propiedad, antes de llamar aca.
 */
public interface FuncionesIaDelCentro {

	/**
	 * {@code true} si el centro no apago la funcion. Un centro sin preferencias guardadas tiene
	 * todas prendidas. {@link FuncionIa#SUGERENCIAS} exige ademas evolucion y biblioteca.
	 */
	boolean prendida(long organizationId, FuncionIa funcion);
}
