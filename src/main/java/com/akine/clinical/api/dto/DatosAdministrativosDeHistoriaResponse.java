package com.akine.clinical.api.dto;

import com.akine.clinical.application.DatosAdministrativosDeHistoriaView;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Los datos administrativos de la Historia Clinica de una persona: el "Limitado" del
 * {@code ADMINISTRATIVO} en la matriz §32 ({@code docs/seguridad/matriz-permisos-minima.md} §4).
 * Nunca contenido clinico.
 */
@Schema(description = "Datos administrativos de la historia clinica de una persona: existencia, "
		+ "fechas y profesionales tratantes. Nunca contenido clinico")
public record DatosAdministrativosDeHistoriaResponse(

		@Schema(example = "12")
		long personaId,

		@Schema(description = "Falso si la persona todavia no tiene historia clinica",
				example = "true")
		boolean tieneHistoriaClinica,

		@Schema(description = "Ausente si la persona no tiene historia", example = "88")
		Long historiaClinicaId,

		@Schema(description = "Instante UTC de apertura. Ausente si la persona no tiene historia")
		Instant abiertaEn,

		@ArraySchema(arraySchema = @Schema(
				description = "Casos de la historia: activos primero y luego cerrados"))
		List<CasoAdministrativoResponse> casos) {

	public static DatosAdministrativosDeHistoriaResponse from(
			DatosAdministrativosDeHistoriaView view) {
		return new DatosAdministrativosDeHistoriaResponse(
				view.personaId(),
				view.tieneHistoriaClinica(),
				view.historiaClinicaId(),
				view.abiertaEn(),
				view.casos().stream().map(CasoAdministrativoResponse::from).toList());
	}
}
