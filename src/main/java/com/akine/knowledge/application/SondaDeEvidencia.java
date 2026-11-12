package com.akine.knowledge.application;

/**
 * Mejor puntaje vectorial de una consulta en la biblioteca de la organizacion. La repregunta solo
 * ofrece los aspectos cuyo puntaje alcanza el corte. Una falla se trata como sin evidencia.
 */
public interface SondaDeEvidencia {

	double mejorPuntaje(long orgId, String consulta);

	/**
	 * Si la biblioteca nombra el tema (la evidencia de {@link BusquedaHibrida}). Sin eso no se ofrecen
	 * aspectos: el coseno sólo no distingue un tema ausente.
	 */
	default boolean nombraElTema(long orgId, String tema) {
		return true;
	}
}
