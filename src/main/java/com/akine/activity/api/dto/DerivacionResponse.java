package com.akine.activity.api.dto;

import com.akine.activity.application.DerivacionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Una derivacion de participante: ids, estado, actores e instantes. <b>Nada clinico</b>: ni
 * diagnostico, ni objetivo, ni el numero de Caso que un profesional dice en voz alta.
 */
@Schema(name = "DerivacionDeParticipante",
		description = "Vinculo entre una participacion de clase y un Caso Clinico (AKINE-08.04).")
public record DerivacionResponse(
		@Schema(example = "501") long id,
		@Schema(example = "301") long inscripcionId,
		@Schema(description = "La asistencia de la que cuelga el vinculo", example = "981")
		long asistenciaId,
		@Schema(example = "512") long personaId,
		@Schema(example = "40") long historiaClinicaId,
		@Schema(example = "88") long casoClinicoId,
		Long planTratamientoId,
		@Schema(description = "Oferta clinica que justifico la derivacion, congelada", example = "45")
		long ofertaId,
		@Schema(description = "Copia congelada de la oferta: si la atencion va a exigir Caso")
		boolean requiereCasoClinico,
		Long autorizacionId,
		@Schema(allowableValues = {"VIGENTE", "REVERTIDA"}, example = "VIGENTE") String estado,
		String motivo,
		Instant derivadaEn,
		Long derivadaPorCuentaId,
		Instant revertidaEn,
		Long revertidaPorCuentaId,
		String motivoReversion,
		@Schema(example = "0") long version,
		@Schema(description = "true si el pedido no creo nada porque el vinculo ya existia")
		boolean yaExistia) {

	public static DerivacionResponse de(DerivacionView v) {
		return new DerivacionResponse(
				v.id(), v.inscripcionId(), v.asistenciaId(), v.personaId(), v.historiaClinicaId(),
				v.casoClinicoId(), v.planTratamientoId(), v.ofertaId(), v.requiereCasoClinico(),
				v.autorizacionId(), v.estado(), v.motivo(), v.derivadaEn(), v.derivadaPorCuentaId(),
				v.revertidaEn(), v.revertidaPorCuentaId(), v.motivoReversion(), v.version(),
				v.yaExistia());
	}
}
