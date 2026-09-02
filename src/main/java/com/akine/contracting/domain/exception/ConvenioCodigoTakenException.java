package com.akine.contracting.domain.exception;

/**
 * Ya existe un convenio VIGENTE con ese codigo en esa sede.
 *
 * <p>El alcance es la SEDE y no la organizacion: dos sedes pueden nombrar "OSDE-210" a su propio
 * acuerdo con el mismo plan, y obligarlas a inventar codigos distintos no protegeria nada.
 *
 * <p>Es una igualdad, asi que <b>si</b> la sostiene un unique ({@code uk_convenio_codigo_vigente}).
 * Es la mitad del problema que un indice sabe resolver; la otra —el solapamiento— no.
 */
public class ConvenioCodigoTakenException extends RuntimeException {

	private final String codigo;

	public ConvenioCodigoTakenException(String codigo) {
		super("Codigo de convenio en uso: " + codigo);
		this.codigo = codigo;
	}

	public String getCodigo() {
		return codigo;
	}
}
