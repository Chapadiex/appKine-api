package com.akine.resource.spi;

/**
 * Que clase de valor admite una definicion de medida (M06, al servicio de RF-M14-004).
 *
 * <p>Vive en {@code spi} y no en {@code domain} porque lo necesitan los dos lados de la arista:
 * {@code resource} para declarar la definicion y {@code encounter} para <b>copiarlo en la fila</b>
 * de la medicion tomada. Un enum equivalente en cada modulo seria una segunda definicion de los
 * mismos cuatro valores que tarde o temprano diverge, y la primera en divergir decidiria que
 * columna de valor es la legitima.
 *
 * <p>El tipo decide que columna de {@code sesion_medicion} puede llevar valor, y el CHECK de V52
 * lo hace cumplir contra el tipo <b>copiado</b> en la fila, no contra el vigente en el catalogo.
 */
public enum MedicionTipo {

	/** Magnitud continua con unidad: ROM en grados, perimetro en cm, fuerza en kg. */
	NUMERICO,

	/**
	 * Numerico con rango acotado y semantica de escala: EVA 0-10, Borg 6-20.
	 *
	 * <p>Para la base es el mismo {@code DECIMAL} que {@link #NUMERICO}; se distingue porque la
	 * pantalla lo dibuja como escala y no como campo libre, y porque un rango sin tope no es una
	 * escala.
	 */
	ESCALA,

	/** Hallazgo descriptivo: "marcha antalgica con claudicacion a los 50 m". */
	TEXTO,

	/** Presencia o ausencia de un signo: Lasegue positivo o negativo. */
	BOOLEANO;

	/** {@code true} si el valor de este tipo vive en {@code valor_numerico}. */
	public boolean esNumerico() {
		return this == NUMERICO || this == ESCALA;
	}
}
