package com.akine.offering.domain.exception;

/**
 * El recurso que se quiere habilitar no existe, es de otro tenant o es de otra sede.
 *
 * <p><b>Los tres casos responden lo mismo, y es a proposito.</b> Distinguirlos confirmaria que ese
 * id existe en alguna parte, y bastaria recorrer numeros para averiguar cuanta gente y cuantos
 * boxes tiene cada centro del SaaS. Mismo criterio que ya usan {@code OfertaNotAccessibleException}
 * y {@code ConsultorioNoAccesibleException}.
 *
 * @param recursoId el id que se pidio habilitar
 * @param tipo      {@code "profesional"} o {@code "espacio"}, para que el mensaje nombre lo que el
 *                  usuario estaba tocando sin revelar nada de lo que no puede ver
 */
public class HabilitacionNoAccesibleException extends RuntimeException {

	private final long recursoId;
	private final String tipo;

	public HabilitacionNoAccesibleException(long recursoId, String tipo) {
		super("Recurso no accesible para habilitar: tipo=" + tipo + " id=" + recursoId);
		this.recursoId = recursoId;
		this.tipo = tipo;
	}

	public long getRecursoId() {
		return recursoId;
	}

	public String getTipo() {
		return tipo;
	}
}
