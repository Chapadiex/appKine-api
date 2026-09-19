package com.akine.clinical.domain.exception;

/**
 * Se intento enmendar una entrada clinica que esta dada de baja.
 *
 * <p><b>Es 409 y no 404</b>, a diferencia de {@link EntradaClinicaNotAccessibleException}: la
 * entrada existe, el actor puede verla y de hecho la etapa la deja consultable por su id —eso es
 * lo que distingue "no lo muestres" de "no existio"—. Lo que no admite es contenido nuevo.
 *
 * <p>Enmendar una entrada de baja produciria una version que nadie va a leer: la entrada esta
 * fuera del timeline. Si el profesional necesita dejar constancia, lo correcto es registrar una
 * entrada nueva, y por eso el rechazo es un conflicto de estado y no un dato faltante.
 */
public class EntradaClinicaInactivaException extends RuntimeException {

	private final long entradaClinicaId;

	public EntradaClinicaInactivaException(long entradaClinicaId) {
		super("La entrada clinica " + entradaClinicaId
				+ " esta dada de baja y no admite enmiendas");
		this.entradaClinicaId = entradaClinicaId;
	}

	public long getEntradaClinicaId() {
		return entradaClinicaId;
	}
}
