package com.akine.person.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Alta de una orden medica (RF-M17-001).
 *
 * <p>El emisor es texto libre porque el medico que firma es <b>externo</b> al centro y no esta en
 * ningun catalogo del tenant. La matricula es opcional: el caso borde "documento ilegible" se
 * resuelve dejandola vacia y cargando lo demas, no rechazando la orden.
 *
 * <p>{@code indicacion} es la transcripcion administrativa de lo que dice el papel. <b>No es
 * registro clinico</b> (RN-M17-004) y no reemplaza nada de M09 ni de M14.
 */
@Schema(description = "Datos de la orden medica que el paciente presento")
public record CreateOrdenRequest(

		@Schema(
				description = "Cobertura para la que se presenta. Omitir = vale para cualquiera. "
						+ "Una prescripcion la firma un medico, no un financiador, asi que atarla "
						+ "a una cobertura obligaria a recargarla cuando el paciente cambia de "
						+ "obra social",
				example = "412",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Long coberturaId,

		@Schema(
				description = "Numero impreso en la orden. Muchas ordenes en papel no lo traen y "
						+ "exigirlo dejaria al mostrador sin poder cargarlas",
				example = "OM-2026-004512",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 64, message = "El numero no puede superar los 64 caracteres")
		String numero,

		@Schema(
				description = "Profesional que firma la orden, tal como figura en el papel",
				example = "Dra. Sintetica Prueba",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El profesional que firma la orden es obligatorio")
		@Size(max = 160, message = "El profesional emisor no puede superar los 160 caracteres")
		String profesionalEmisor,

		@Schema(
				description = "Matricula del emisor, cuando esta legible",
				example = "MP 12345",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 64, message = "La matricula no puede superar los 64 caracteres")
		String matriculaEmisor,

		@Schema(
				description = "Fecha que la orden declara. Es lo que el financiador mira para "
						+ "aceptar o rechazar la presentacion",
				example = "2026-09-01",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La fecha de emision de la orden es obligatoria")
		LocalDate fechaEmision,

		@Schema(
				description = "Transcripcion administrativa de lo indicado. NO es registro clinico "
						+ "(RN-M17-004): la documentacion administrativa no reemplaza la historia "
						+ "clinica",
				example = "Kinesiologia motora, 10 sesiones",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 280, message = "La indicacion no puede superar los 280 caracteres")
		String indicacion,

		@Schema(
				description = "Sesiones que la orden indica. INFORMATIVA: quien limita lo que se "
						+ "puede atender es la autorizacion del financiador, no la prescripcion",
				example = "10",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Positive(message = "Las sesiones prescriptas tienen que ser mayores que cero")
		Integer sesionesPrescriptas,

		@Schema(
				description = "Primer dia en que la orden se puede presentar. Omitir = la fecha de "
						+ "emision. Nunca puede ser anterior a ella",
				example = "2026-09-01",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaDesde,

		@Schema(
				description = "ULTIMO dia, INCLUSIVE. Null = sin vencimiento declarado. Una orden "
						+ "vencida NO desaparece: se sigue listando con vencida = true",
				example = "2026-12-31",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(description = "Notas administrativas. NUNCA contenido clinico",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 500, message = "Las observaciones no pueden superar los 500 caracteres")
		String observaciones) {
}
