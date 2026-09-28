package com.akine.activity.domain.exception;

/**
 * La transicion pedida no existe en la maquina de estados de la clase, o su ventana no la admite.
 *
 * <p>Un solo tipo para las dos familias —"ya esta cancelada" y "ya empezo"— con el motivo como
 * propiedad extra, igual que en M12: para la pantalla el desenlace es el mismo.
 */
public class TransicionDeClaseNoPermitidaException extends RuntimeException {

	private final Long claseId;
	private final String motivo;

	public TransicionDeClaseNoPermitidaException(Long claseId, String motivo) {
		super("La clase " + claseId + " no admite la operacion: " + motivo);
		this.claseId = claseId;
		this.motivo = motivo;
	}

	public Long getClaseId() {
		return claseId;
	}

	public String getMotivo() {
		return motivo;
	}
}
