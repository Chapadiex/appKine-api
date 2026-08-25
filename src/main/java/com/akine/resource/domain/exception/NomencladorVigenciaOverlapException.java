package com.akine.resource.domain.exception;

import java.time.Instant;

/**
 * Dos vigencias del mismo codigo del nomenclador se pisan (409).
 *
 * <p>Es la validacion de "vigencias superpuestas" de la etapa, y la razon por la que existe
 * {@code nomenclador_item} como tabla aparte. Si dos vigencias del mismo codigo se solaparan,
 * la pregunta que RN-M06-003 necesita responder —"que valor regia el dia D"— tendria <b>dos</b>
 * respuestas, y cual gana dependeria del orden del indice. Un convenio presentado a un
 * financiador no puede depender de eso.
 *
 * <p><b>Dos vigencias consecutivas NO se solapan:</b> los limites superiores son exclusivos, asi
 * que cerrar una en el instante T y abrir la siguiente en el mismo T es correcto, y es el camino
 * normal de una actualizacion de valores.
 *
 * <p>La ventana en conflicto viaja en el cuerpo para que la pantalla pueda decir exactamente
 * contra que choco, en vez de obligar al usuario a recorrer la lista buscandolo.
 */
public class NomencladorVigenciaOverlapException extends RuntimeException {

	private final String codigo;
	private final Instant desdeExistente;
	private final Instant hastaExistente;

	public NomencladorVigenciaOverlapException(
			String codigo, Instant desdeExistente, Instant hastaExistente) {

		super("La vigencia del codigo " + codigo + " se solapa con una existente");
		this.codigo = codigo;
		this.desdeExistente = desdeExistente;
		this.hastaExistente = hastaExistente;
	}

	public String getCodigo() {
		return codigo;
	}

	public Instant getDesdeExistente() {
		return desdeExistente;
	}

	public Instant getHastaExistente() {
		return hastaExistente;
	}
}
