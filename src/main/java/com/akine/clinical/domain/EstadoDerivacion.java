package com.akine.clinical.domain;

/**
 * Los dos estados de una derivacion al circuito clinico.
 *
 * <p>No hay un tercero, y no es una simplificacion: derivar es un hecho binario. O esta
 * participacion pertenece a este Caso o no pertenece.
 *
 * <p><b>Y no hay baja logica.</b> El cuarteto {@code active}/{@code deleted_at}/
 * {@code deactivation_reason}/{@code deleted_key} que usa el resto del repositorio dice "esta fila
 * ya no cuenta para nadie". Una derivacion no se da de baja: se <b>deshace</b>, y lo que alguien va
 * a querer leer despues es "este paciente NO pertenece a este Caso, y aca esta quien lo decidio y
 * por que". Por eso {@link #REVERTIDA} y no una baja.
 */
public enum EstadoDerivacion {

	/** El vinculo esta en pie. */
	VIGENTE,

	/**
	 * El vinculo se deshizo, con motivo, actor e instante. La fila queda para siempre.
	 *
	 * <p>Volver a derivar la misma participacion al mismo Caso es legitimo despues de esto: lo
	 * habilita {@code revertida_key}, porque una reversion que no deja volver a derivar no
	 * revierte nada.
	 */
	REVERTIDA;

	/** {@code true} si el vinculo esta en pie. */
	public boolean estaVigente() {
		return this == VIGENTE;
	}
}
