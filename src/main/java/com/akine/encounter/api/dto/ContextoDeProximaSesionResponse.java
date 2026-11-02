package com.akine.encounter.api.dto;

import com.akine.encounter.application.ContextoDeProximaSesionView;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "ContextoDeProximaSesion", description = "Lo que se sabe para programar la proxima sesion. Nada clinico.")
public record ContextoDeProximaSesionResponse(
		@Schema(example = "128") long personaId,
		@Schema(example = "9") Long casoId,
		@Schema(description = "El plan del turno que se atendio, si tenia", example = "57") Long planId,
		@Schema(example = "42") long ofertaId,
		@Schema(example = "31") long profesionalId) {

	public static ContextoDeProximaSesionResponse de(ContextoDeProximaSesionView vista) {
		return new ContextoDeProximaSesionResponse(vista.personaId(), vista.casoId(),
				vista.planId(), vista.ofertaId(), vista.profesionalId());
	}
}
