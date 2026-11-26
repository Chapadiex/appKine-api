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

	/**
	 * {@code true} si el centro acepto la version vigente del consentimiento de IA (Ley 25.326), o si
	 * el despliegue no lo exige. Sin el, ninguna funcion de IA corre, local o externa. {@link #prendida}
	 * ya lo incluye; lo usan las funciones que no pasan por ahi, como el dictado.
	 */
	default boolean consentimientoVigente(long organizationId) {
		return true;
	}
}
