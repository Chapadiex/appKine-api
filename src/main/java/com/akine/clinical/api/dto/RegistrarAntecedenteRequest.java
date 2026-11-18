package com.akine.clinical.api.dto;

import com.akine.clinical.application.DetalleDeAntecedente;
import com.akine.clinical.domain.EnfermedadDeIngreso;
import com.akine.clinical.domain.TipoAntecedente;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Alta de un antecedente clinico en la historia de una persona (RF-M09-002). */
@Schema(description = "Alta de un antecedente clinico")
public record RegistrarAntecedenteRequest(

		@Schema(description = "Clase de antecedente", example = "ALERGIA",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El tipo de antecedente es obligatorio")
		TipoAntecedente tipo,

		@Schema(description = "Texto clinico del antecedente",
				example = "Alergia a la penicilina",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "La descripcion del antecedente es obligatoria")
		@Size(max = 1000, message = "La descripcion no puede superar los 1000 caracteres")
		String descripcion,

		@Schema(description = "Ficha de ingreso: zona de una cirugia", example = "Rodilla derecha")
		@Size(max = 120, message = "La zona no puede superar los 120 caracteres")
		String zona,

		@Schema(description = "Ficha de ingreso: año de una cirugia (1900 al actual)", example = "2019")
		@Min(value = 1900, message = "El año tiene que ser 1900 o posterior")
		@Max(value = 2100, message = "El año no es valido")
		Short anio,

		@Schema(description = "Ficha de ingreso: donde se hizo la cirugia", example = "Hospital Privado")
		@Size(max = 160, message = "El lugar no puede superar los 160 caracteres")
		String lugar,

		@Schema(description = "Ficha de ingreso: enfermedad de la lista. Exige tipo MEDICO; si ya hay una "
				+ "vigente con ese codigo se devuelve esa, sin otra alta", example = "DIABETES")
		EnfermedadDeIngreso codigo) {

	/** Los cuatro campos opcionales de la ficha de ingreso. El año futuro se rechaza ahi (400). */
	public DetalleDeAntecedente detalle() {
		return new DetalleDeAntecedente(zona, anio, lugar, codigo);
	}
}
