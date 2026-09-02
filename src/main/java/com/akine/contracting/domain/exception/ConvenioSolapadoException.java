package com.akine.contracting.domain.exception;

/**
 * Ya existe un convenio vigente para la misma (sede, financiador, plan) cuyo periodo se pisa con
 * el pedido. <b>Es la excepcion que define AKINE-03.05</b> (RN-M16-002).
 *
 * <p>No la produce ningun unique de la base y no podria: dos periodos que se cruzan no comparten
 * ningun valor de columna, y MySQL 8.4 no tiene exclusion constraints. La produce
 * {@code ConvenioService} despues de tomar el lock de {@code convenio_lock}, que es lo unico que
 * garantiza que dos escrituras concurrentes no la esquiven las dos.
 *
 * <p>Lleva el id y el periodo del convenio con el que choca porque un 409 que solo dice "se
 * solapa" obliga al administrador a buscar a mano cual de los suyos es. El periodo viaja como
 * texto ya formado: quien lo consume es una pantalla, no un calculo.
 */
public class ConvenioSolapadoException extends RuntimeException {

	private final long convenioExistenteId;

	private final String periodoExistente;

	public ConvenioSolapadoException(long convenioExistenteId, String periodoExistente) {
		super("Convenio solapado con " + convenioExistenteId + " (" + periodoExistente + ")");
		this.convenioExistenteId = convenioExistenteId;
		this.periodoExistente = periodoExistente;
	}

	public long getConvenioExistenteId() {
		return convenioExistenteId;
	}

	public String getPeriodoExistente() {
		return periodoExistente;
	}
}
