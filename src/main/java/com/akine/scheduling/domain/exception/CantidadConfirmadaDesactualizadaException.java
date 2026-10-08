package com.akine.scheduling.domain.exception;

/**
 * La cantidad de turnos que el operador confirmo al cancelar o reprogramar una serie ya no es la
 * que afecta el alcance bajo el lock. <b>409 {@code conflict}.</b>
 *
 * <p>Es la confirmacion explicita de DP-04 hecha contrato: lo que el operador vio en la
 * previsualizacion tiene que ser lo que se ejecuta, o no se toca nada.
 *
 * <p><b>Por que no es {@code concurrent-modification}</b> (DP-21). Hasta DP-21 se lanzaba como
 * {@code OptimisticLockingFailureException}, que desde entonces se traduce a
 * {@code concurrent-modification}: "otra persona modifico este registro, recargalo". Aca no hay una
 * {@code version} vieja de ningun registro —la serie no cambio de version, cambio cuantos turnos
 * caen en el alcance— y lo que el operador tiene que hacer no es recargar y repetir el mismo click,
 * sino <b>volver a previsualizar</b> y decidir de nuevo sobre una lista distinta. Es un conflicto de
 * negocio y queda en {@code conflict}, como antes de DP-21.
 */
public class CantidadConfirmadaDesactualizadaException extends RuntimeException {

	private final long serieId;
	private final int afectados;
	private final int confirmados;

	public CantidadConfirmadaDesactualizadaException(
			long serieId, String alcance, int afectados, int confirmados) {
		super("La serie " + serieId + " cambio desde la previsualizacion: el alcance " + alcance
				+ " afecta " + afectados + " turnos y se confirmaron " + confirmados);
		this.serieId = serieId;
		this.afectados = afectados;
		this.confirmados = confirmados;
	}

	public long getSerieId() {
		return serieId;
	}

	public int getAfectados() {
		return afectados;
	}

	public int getConfirmados() {
		return confirmados;
	}
}
