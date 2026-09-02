package com.akine.contracting.domain.exception;

/**
 * Ya hay un plan VIGENTE con ese codigo en ESE financiador.
 *
 * <p>El alcance es el financiador y no la organizacion: dos financiadores distintos pueden tener
 * los dos un plan "210", y obligar a que no se repita entre ellos forzaria a inventar codigos que
 * el financiador real no usa.
 */
public class PlanCodigoTakenException extends RuntimeException {

	private final String codigo;

	public PlanCodigoTakenException(String codigo) {
		super("Codigo de plan en uso");
		this.codigo = codigo;
	}

	public String getCodigo() {
		return codigo;
	}
}
