package com.akine.clinical.application;

import com.akine.clinical.domain.RolEnCaso;

/**
 * Quien participa en el caso y con que rol (RF-M10-005).
 *
 * <p>Es la <b>membership</b> y no la cuenta: es lo que identifica al profesional en ESTE centro, y
 * la misma persona puede ser profesional en uno y administrativa en otro. Misma decision que V23
 * para la disponibilidad y V28 para las habilitaciones.
 *
 * <p>El rol no otorga ni quita permisos. Quien puede escribir en la historia lo decide
 * {@code hc:write} y la membership vigente; esto expresa quien responde por el caso, que es un
 * dato clinico-organizativo.
 */
public record IntegranteDelEquipo(long profesionalMembershipId, RolEnCaso rol) {

	public IntegranteDelEquipo {
		if (rol == null) {
			rol = RolEnCaso.TRATANTE;
		}
	}
}
