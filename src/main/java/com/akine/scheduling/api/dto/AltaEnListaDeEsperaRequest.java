package com.akine.scheduling.api.dto;

import com.akine.scheduling.domain.PrioridadListaDeEspera;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Schema(name = "AltaEnListaDeEspera", description = "Que lugar le serviria a esta persona.")
public record AltaEnListaDeEsperaRequest(

		@Schema(example = "128")
		@NotNull(message = "La persona es obligatoria")
		@Positive(message = "La persona no es valida") Long personaId,
		@Schema(example = "42")
		@NotNull(message = "La oferta es obligatoria")
		@Positive(message = "La oferta no es valida") Long ofertaId,
		@Schema(description = "Profesional preferido. Omitirlo es cualquiera habilitado.", example = "31")
		@Positive(message = "El profesional no es valido") Long profesionalId,
		@Schema(example = "9") @Positive(message = "El caso no es valido") Long casoId,
		@Schema(example = "57") @Positive(message = "El plan no es valido") Long planId,
		@Schema(example = "2026-11-02") @NotNull(message = "La fecha desde es obligatoria") LocalDate fechaDesde,
		@Schema(description = "Ultimo dia que le sirve, INCLUSIVO", example = "2026-11-30")
		@NotNull(message = "La fecha hasta es obligatoria") LocalDate fechaHasta,

		@ArraySchema(
				arraySchema = @Schema(description = "Dias posibles, ISO: 1 = lunes ... 7 = domingo. Omitirlo es cualquiera."),
				schema = @Schema(example = "1", minimum = "1", maximum = "7"))
		@Size(max = 7, message = "Los dias no pueden ser mas de 7")
		List<@NotNull(message = "Los dias no pueden tener valores vacios")
				@Min(value = 1, message = "Cada uno de los dias debe estar entre 1 y 7")
				@Max(value = 7, message = "Cada uno de los dias debe estar entre 1 y 7")
				Integer> dias,

		@Schema(type = "string", format = "time", example = "14:00:00") LocalTime horaDesde,
		@Schema(description = "EXCLUSIVA. Viene junto con horaDesde o no viene ninguna.", type = "string", format = "time", example = "18:00:00") LocalTime horaHasta,
		@Schema(description = "Por defecto NORMAL") PrioridadListaDeEspera prioridad,
		@Schema(description = "Preferencias operativas. NUNCA informacion clinica.")
		@Size(max = 300, message = "Las notas no pueden superar los 300 caracteres") String notas) {
}
