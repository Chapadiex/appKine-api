package com.akine.activity.domain.exception;

/**
 * No queda lugar y quien inscribe <b>no acepto la lista de espera</b> (RF-M28-002).
 *
 * <p>Tipo propio y no un {@code conflict} generico porque el plan lo pide explicitamente —"error
 * especifico de clase completa"— y porque el desenlace en pantalla es distinto de cualquier otro
 * 409: no hay nada que corregir en el formulario, lo que hay es una alternativa que ofrecer.
 *
 * <p>Por eso lleva {@code capacidadEfectiva} y {@code ocupados}: con esos dos numeros la pantalla
 * puede ofrecer la lista de espera sin otra vuelta al servidor. Misma idea que
 * {@code capacidadMaxima} en {@link CapacidadNoAdmitidaException}.
 */
public class ClaseCompletaException extends RuntimeException {

	private final long claseId;
	private final int capacidadEfectiva;
	private final int ocupados;

	public ClaseCompletaException(long claseId, int capacidadEfectiva, int ocupados) {
		super("La clase " + claseId + " no tiene lugares disponibles ("
				+ ocupados + " de " + capacidadEfectiva + ")");
		this.claseId = claseId;
		this.capacidadEfectiva = capacidadEfectiva;
		this.ocupados = ocupados;
	}

	public long getClaseId() {
		return claseId;
	}

	public int getCapacidadEfectiva() {
		return capacidadEfectiva;
	}

	public int getOcupados() {
		return ocupados;
	}
}
