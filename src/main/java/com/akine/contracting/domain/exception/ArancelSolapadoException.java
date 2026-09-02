package com.akine.contracting.domain.exception;

/**
 * Ya existe un arancel vigente de la MISMA practica en el mismo convenio cuyo periodo se pisa con
 * el pedido (RN-M16-002).
 *
 * <p>Dos aranceles de la misma practica son el caso normal —el de 2026 y el de 2027— y por eso no
 * hay ningun unique de {@code (convenio_id, practica_id)}. Lo prohibido es que se PISEN, y eso lo
 * decide {@code ArancelService} bajo el lock de {@code convenio_lock}. Ver
 * {@link ConvenioSolapadoException}.
 */
public class ArancelSolapadoException extends RuntimeException {

	private final long arancelExistenteId;

	private final String periodoExistente;

	public ArancelSolapadoException(long arancelExistenteId, String periodoExistente) {
		super("Arancel solapado con " + arancelExistenteId + " (" + periodoExistente + ")");
		this.arancelExistenteId = arancelExistenteId;
		this.periodoExistente = periodoExistente;
	}

	public long getArancelExistenteId() {
		return arancelExistenteId;
	}

	public String getPeriodoExistente() {
		return periodoExistente;
	}
}
