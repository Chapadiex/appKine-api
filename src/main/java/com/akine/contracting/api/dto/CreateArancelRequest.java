package com.akine.contracting.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Alta de un arancel bajo un convenio (RF-M16-004).
 *
 * <p><b>Tres importes explicitos y ningun porcentaje de cobertura.</b> Un porcentaje obliga a
 * multiplicar y redondear, §37 exige documentar todo redondeo, y el redondeo de un arancel es el
 * tipo de diferencia de un centavo que aparece seis meses despues en una presentacion rechazada.
 * Con tres importes no hay nada que redondear: la suma de dos decimales de dos posiciones es
 * exacta.
 *
 * <p>La moneda no viaja: la hereda del convenio.
 */
@Schema(description = "Datos para definir el arancel de una practica bajo un convenio")
public record CreateArancelRequest(

		@Schema(description = "Practica del catalogo clinico que se arancela", example = "412",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El arancel necesita una practica")
		@Positive(message = "El identificador de la practica tiene que ser positivo")
		Long practicaId,

		@Schema(
				description = "Lo que vale la practica bajo este convenio. Cero es valido y "
						+ "significa una practica sin cargo",
				example = "12000.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El importe total es obligatorio")
		@DecimalMin(value = "0.00", message = "El importe total no puede ser negativo")
		@Digits(integer = 10, fraction = 2, message = "Los importes admiten 2 decimales")
		BigDecimal importeTotal,

		@Schema(
				description = "La parte que paga el financiador. Junto con el coseguro tiene que "
						+ "sumar EXACTAMENTE el importe total, o la peticion se rechaza con 400",
				example = "9600.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La parte del financiador es obligatoria")
		@DecimalMin(value = "0.00", message = "La parte del financiador no puede ser negativa")
		@Digits(integer = 10, fraction = 2, message = "Los importes admiten 2 decimales")
		BigDecimal importeFinanciador,

		@Schema(
				description = "La parte que paga el paciente. Cero es valido y significa cobertura "
						+ "total",
				example = "2400.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El coseguro es obligatorio")
		@DecimalMin(value = "0.00", message = "El coseguro no puede ser negativo")
		@Digits(integer = 10, fraction = 2, message = "Los importes admiten 2 decimales")
		BigDecimal coseguro,

		@Schema(description = "Primer dia en que este importe se aplica", example = "2026-01-01",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La vigencia del arancel necesita una fecha de inicio")
		LocalDate vigenciaDesde,

		@Schema(
				description = "ULTIMO dia en que se aplica, INCLUSIVE. Null significa sin fin "
						+ "previsto. Tiene que estar contenido en la vigencia del convenio",
				example = "2026-06-30",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(
				description = "Opcional (RF-M16-008). Sin oferta, el arancel es el GENERAL de la practica. "
						+ "Con oferta, es el arancel de la practica cuando se presta dentro de esa "
						+ "oferta, y al resolver con esa oferta manda sobre el general. La oferta tiene "
						+ "que ser de la misma sede, admitir obra social y declarar la practica (A-9)",
				example = "77",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Positive(message = "El identificador de la oferta tiene que ser positivo")
		Long ofertaId) {
}
