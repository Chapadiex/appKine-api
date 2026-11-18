package com.akine.clinical.api.dto;

import com.akine.clinical.application.FichaDeIngresoService.DatosDeFicha;
import com.akine.clinical.domain.ActividadFisica;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * La ficha de ingreso entera. Cada guardado la reemplaza: un campo que viene null queda vacio.
 * {@code version} es la leida, o null si la ficha todavia no existe.
 */
@Schema(description = "Ficha de ingreso del paciente: se guarda entera, con la version leida")
public record FichaDeIngresoRequest(

		@Schema(description = "Ocupacion del catalogo (GET /ocupaciones), o null", example = "17")
		Long ocupacionId,

		@Schema(description = "Peso en kg, de 1 a 400, un decimal", example = "72.5")
		@DecimalMin(value = "1", message = "El peso tiene que ser de al menos 1 kg")
		@DecimalMax(value = "400", message = "El peso no puede superar los 400 kg")
		@Digits(integer = 3, fraction = 1, message = "El peso lleva a lo sumo un decimal")
		BigDecimal pesoKg,

		@Schema(description = "Altura en cm, de 30 a 250, un decimal", example = "168")
		@DecimalMin(value = "30", message = "La altura tiene que ser de al menos 30 cm")
		@DecimalMax(value = "250", message = "La altura no puede superar los 250 cm")
		@Digits(integer = 3, fraction = 1, message = "La altura lleva a lo sumo un decimal")
		BigDecimal alturaCm,

		@Schema(description = "Nivel de actividad fisica, o null", example = "REGULAR")
		ActividadFisica actividad,

		@Schema(description = "Que actividad hace, texto corto", example = "Camina 3 veces por semana")
		@Size(max = 120, message = "El detalle de la actividad no puede superar los 120 caracteres")
		String detalle,

		@Schema(description = "Version leida de la ficha; null si todavia no existe", example = "3")
		Long version) {

	public DatosDeFicha datos() {
		return new DatosDeFicha(ocupacionId, pesoKg, alturaCm, actividad, detalle);
	}
}
