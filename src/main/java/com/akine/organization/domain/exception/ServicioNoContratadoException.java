package com.akine.organization.domain.exception;

import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.Servicio;

/**
 * El centro no contrato el servicio (o el adicional) que la operacion necesita (18/11/2026).
 * Se responde como una funcionalidad fuera del plan: 409 {@code feature-not-available}.
 */
public class ServicioNoContratadoException extends RuntimeException {

	private final String contratable;

	public ServicioNoContratadoException(Servicio servicio) {
		super("Servicio no contratado: " + servicio);
		this.contratable = servicio.name();
	}

	public ServicioNoContratadoException(Adicional adicional) {
		super("Adicional no contratado: " + adicional);
		this.contratable = adicional.name();
	}

	/** {@code KINESIOLOGIA}, {@code ACTIVIDADES} o {@code IA}. */
	public String getContratable() {
		return contratable;
	}
}
