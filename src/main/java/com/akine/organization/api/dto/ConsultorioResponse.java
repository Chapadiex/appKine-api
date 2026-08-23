package com.akine.organization.api.dto;

import com.akine.organization.application.ConsultorioView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Sede del tenant.
 *
 * <p>Lectura minima a proposito: la configuracion completa del consultorio —horarios,
 * profesionales, agenda— llega en 02.01. Lo que 01.01 necesita publicar es lo suficiente para
 * armar el selector de contexto de trabajo (ADR-0009).
 */
@Schema(description = "Sede (consultorio) de la organizacion")
public record ConsultorioResponse(

		@Schema(description = "Identificador del consultorio", example = "1")
		long id,

		@Schema(description = "Organizacion a la que pertenece", example = "1")
		long organizationId,

		@Schema(description = "Nombre de la sede", example = "Sede Central")
		String name,

		@Schema(description = "false cuando la sede fue dada de baja logica", example = "true")
		boolean active) {

	public static ConsultorioResponse from(ConsultorioView view) {
		return new ConsultorioResponse(
				view.id(), view.organizationId(), view.name(), view.active());
	}
}
