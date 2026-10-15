package com.akine.activity.api.dto;

import com.akine.activity.application.EstadoDeDerivacionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

@Schema(name = "DerivacionesDeParticipante",
		description = "Las derivaciones de una participacion, vigentes y revertidas, mas nueva "
				+ "primero. Lectura clinica: se autoriza y se audita.")
public record EstadoDeDerivacionResponse(
		@Schema(description = "null si la persona todavia no tiene historia: la derivacion la abre")
		Long historiaClinicaId,
		List<DerivacionResponse> derivaciones) {

	public static EstadoDeDerivacionResponse de(EstadoDeDerivacionView v) {
		return new EstadoDeDerivacionResponse(
				v.historiaClinicaId(),
				v.derivaciones().stream().map(DerivacionResponse::de).toList());
	}
}
