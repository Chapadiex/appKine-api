package com.akine.clinical.domain.exception;

/**
 * La autorizacion declarada al derivar no habilita ese dia (409).
 *
 * <p><b>409 y no 404</b>: la autorizacion existe, es de ese paciente y es de ese tenant —eso ya lo
 * resolvio {@code AutorizacionDirectory}, que colapsa a vacio los cuatro casos en que no lo es—.
 * Lo que pasa es que no sirve, y el operador puede hacer algo al respecto: pedir otra, o derivar
 * sin declararla.
 *
 * <p>{@code motivo} viaja calculado por {@code person} —{@code VENCIDA}, {@code AGOTADA},
 * {@code AUN_NO_VIGENTE} o {@code NO_APROBADA}— y no se reinterpreta aca: recalcularlo duplicaria
 * la regla en dos modulos.
 */
public class AutorizacionNoElegibleException extends RuntimeException {

	private final long autorizacionId;
	private final String motivo;

	public AutorizacionNoElegibleException(long autorizacionId, String motivo) {
		super("La autorizacion " + autorizacionId + " no habilita: " + motivo);
		this.autorizacionId = autorizacionId;
		this.motivo = motivo;
	}

	public long getAutorizacionId() {
		return autorizacionId;
	}

	public String getMotivo() {
		return motivo;
	}
}
