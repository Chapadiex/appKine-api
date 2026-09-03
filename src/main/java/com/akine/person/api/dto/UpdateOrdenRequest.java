package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Edicion de una orden medica. Lo que no viaja no se toca.
 *
 * <p><b>No lleva la persona ni la cobertura</b>, y no es un olvido: son inmutables. Mudar una
 * orden de paciente reescribiria quien presento que papel, que es el historico que la regla
 * maestra 10 protege.
 *
 * <p>Una orden <b>vencida</b> si se edita, y hace falta: corregir la fecha mal tipeada es el caso
 * normal. Una dada de baja no, porque reabrirla reescribiria el historico.
 */
@Schema(description = "Cambios sobre una orden medica")
public record UpdateOrdenRequest(

		@Schema(description = "Numero impreso en la orden", example = "OM-2026-004512")
		@Size(max = 64, message = "El numero no puede superar los 64 caracteres")
		String numero,

		@Schema(description = "Profesional que firma", example = "Dra. Sintetica Prueba")
		@Size(max = 160, message = "El profesional emisor no puede superar los 160 caracteres")
		String profesionalEmisor,

		@Schema(description = "Matricula del emisor", example = "MP 12345")
		@Size(max = 64, message = "La matricula no puede superar los 64 caracteres")
		String matriculaEmisor,

		@Schema(description = "Fecha que la orden declara", example = "2026-09-01")
		LocalDate fechaEmision,

		@Schema(description = "Transcripcion administrativa. NO es registro clinico")
		@Size(max = 280, message = "La indicacion no puede superar los 280 caracteres")
		String indicacion,

		@Schema(description = "Sesiones indicadas. Informativa", example = "10")
		@Positive(message = "Las sesiones prescriptas tienen que ser mayores que cero")
		Integer sesionesPrescriptas,

		@Schema(description = "Primer dia de vigencia. Nunca anterior a la emision")
		LocalDate vigenciaDesde,

		@Schema(description = "ULTIMO dia de vigencia, INCLUSIVE")
		LocalDate vigenciaHasta,

		@Schema(description = "Notas administrativas. NUNCA contenido clinico")
		@Size(max = 500, message = "Las observaciones no pueden superar los 500 caracteres")
		String observaciones,

		@Schema(
				description = "Version que el cliente leyo. Una version vieja responde 409 en vez "
						+ "de pisar el cambio ajeno en silencio",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		long expectedVersion) {
}
