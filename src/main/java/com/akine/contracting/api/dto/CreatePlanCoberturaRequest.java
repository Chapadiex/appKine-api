package com.akine.contracting.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Alta de un plan bajo un financiador.
 *
 * <p>El financiador viene de la ruta y no del cuerpo: RN-M15-001 dice que un plan pertenece a un
 * financiador, y ponerlo en el cuerpo permitiria un request cuya URL dice una cosa y cuyo cuerpo
 * dice otra.
 */
@Schema(description = "Datos para dar de alta un plan de cobertura")
public record CreatePlanCoberturaRequest(

		@Schema(
				description = "Clave estable del plan DENTRO del financiador. NO se puede cambiar. "
						+ "Unico entre los planes vigentes de ese financiador: dos financiadores "
						+ "distintos si pueden tener los dos un plan 210",
				example = "210",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El codigo del plan es obligatorio")
		@Size(max = 64, message = "El codigo no puede superar los 64 caracteres")
		String codigo,

		@Schema(description = "Nombre visible del plan", example = "Plan 210",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre del plan es obligatorio")
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String nombre,

		@Schema(description = "Descripcion administrativa. Nunca contenido clinico",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 500, message = "La descripcion no puede superar los 500 caracteres")
		String descripcion,

		@Schema(
				description = "Primer dia en que el plan se puede elegir para una cobertura nueva",
				example = "2026-01-01",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La vigencia del plan necesita una fecha de inicio")
		LocalDate vigenciaDesde,

		@Schema(
				description = "ULTIMO dia en que se puede elegir, INCLUSIVE. Null significa sin "
						+ "fin previsto. Puede coincidir con vigenciaDesde: un plan que vale un "
						+ "solo dia es un estado real",
				example = "2026-12-31",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(
				description = "Si las prestaciones bajo este plan exigen autorizacion previa. Se "
						+ "declara y todavia nadie lo interpreta: eso es M17. Ausente = false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereAutorizacion,

		@Schema(
				description = "Si la cobertura del paciente bajo este plan exige numero de "
						+ "credencial. Ausente = TRUE, al reves que el anterior: pedir la "
						+ "credencial es lo normal en una cobertura financiada",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereCredencial,

		@Schema(
				description = "Copago de referencia. Null = sin copago declarado, que NO es lo "
						+ "mismo que cero. Viaja siempre con moneda: los dos o ninguno",
				example = "1500.00",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@DecimalMin(value = "0.00", message = "El copago no puede ser negativo")
		@Digits(integer = 10, fraction = 2, message = "El copago admite 2 decimales")
		BigDecimal copago,

		@Schema(description = "ISO 4217 del copago", example = "ARS",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(min = 3, max = 3, message = "La moneda se declara con su codigo ISO 4217 de 3 letras")
		String moneda) {
}
