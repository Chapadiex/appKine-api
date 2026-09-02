package com.akine.contracting.api.dto;

import com.akine.contracting.domain.ModalidadConvenio;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Alta de un convenio (RF-M16-001).
 *
 * <p>La sede viene de la ruta y no del cuerpo: el convenio es contextual al consultorio
 * (RN-M16-001), y ponerla en el cuerpo permitiria un request cuya URL dice una cosa y cuyo cuerpo
 * dice otra.
 */
@Schema(description = "Datos para dar de alta un convenio de la sede con un plan de un financiador")
public record CreateConvenioRequest(

		@Schema(description = "Financiador con el que se negocia", example = "31",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El convenio necesita un financiador")
		@Positive(message = "El identificador del financiador tiene que ser positivo")
		Long financiadorId,

		@Schema(
				description = "Plan de cobertura del financiador. Obligatorio: es lo que hace que la "
						+ "resolucion del arancel tenga como mucho una candidata",
				example = "88",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El convenio necesita un plan")
		@Positive(message = "El identificador del plan tiene que ser positivo")
		Long planId,

		@Schema(
				description = "Clave estable del convenio DENTRO de la sede. NO se puede cambiar: "
						+ "es lo que un snapshot economico guarda para explicarse seis meses "
						+ "despues. Otra sede si puede usar el mismo codigo",
				example = "OSDE-210-2026",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El codigo del convenio es obligatorio")
		@Size(max = 64, message = "El codigo no puede superar los 64 caracteres")
		String codigo,

		@Schema(description = "Nombre visible del convenio", example = "OSDE 210 - 2026",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El nombre del convenio es obligatorio")
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String nombre,

		@Schema(
				description = "Como se pacto la prestacion. CLASIFICA, no habilita: ninguna decision "
						+ "del sistema depende de este valor",
				example = "POR_PRESTACION",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La modalidad del convenio es obligatoria")
		ModalidadConvenio modalidad,

		@Schema(description = "Primer dia en que el convenio se aplica", example = "2026-01-01",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La vigencia del convenio necesita una fecha de inicio")
		LocalDate vigenciaDesde,

		@Schema(
				description = "ULTIMO dia en que se aplica, INCLUSIVE. Null significa sin fin "
						+ "previsto. Puede coincidir con vigenciaDesde",
				example = "2026-12-31",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(
				description = "ISO 4217 de los aranceles de este convenio. Obligatoria: todos sus "
						+ "aranceles la heredan, y un arancel en otra moneda que su convenio seria "
						+ "un dato roto",
				example = "ARS",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La moneda del convenio es obligatoria")
		@Size(min = 3, max = 3, message = "La moneda se declara con su codigo ISO 4217 de 3 letras")
		String moneda,

		@Schema(description = "Si la prestacion exige orden medica. Se declara; lo interpreta M17. "
				+ "Ausente = false", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereOrden,

		@Schema(description = "Si exige autorizacion previa del financiador. Ausente = false",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereAutorizacion,

		@Schema(
				description = "Si exige numero de credencial del paciente. Ausente = TRUE, al reves "
						+ "que los otros dos: pedir la credencial es lo normal en una prestacion "
						+ "financiada",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereCredencial,

		@Schema(
				description = "Tope de sesiones por mes pactado. Omitir significa SIN tope; cero no "
						+ "es un valor valido",
				example = "20",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Min(value = 1, message = "El tope mensual tiene que ser mayor que cero")
		Integer limiteSesionesMensual,

		@Schema(description = "Documentacion administrativa que el financiador exige. Nunca "
				+ "contenido clinico", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 500, message = "La documentacion requerida no puede superar los 500 caracteres")
		String documentacionRequerida,

		@Schema(description = "Notas administrativas. Nunca contenido clinico",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 500, message = "Las observaciones no pueden superar los 500 caracteres")
		String observaciones) {
}
