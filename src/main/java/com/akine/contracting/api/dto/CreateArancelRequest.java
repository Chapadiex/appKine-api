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
				description = "OBSOLETO desde 0.189.0: se ignora. El coseguro se calcula al cerrar "
						+ "la sesion como precio de la oferta menos lo que paga el financiador",
				example = "12000.00", deprecated = true,
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		BigDecimal importeTotal,

		@Schema(
				description = "Lo que paga el financiador por la practica (B). El paciente paga "
						+ "la diferencia con el precio de la oferta, nunca menos de cero",
				example = "9600.00",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "Lo que paga el financiador es obligatorio")
		@DecimalMin(value = "0.00", message = "Lo que paga el financiador no puede ser negativo")
		@Digits(integer = 10, fraction = 2, message = "Los importes admiten 2 decimales")
		BigDecimal importeFinanciador,

		@Schema(
				description = "OBSOLETO desde 0.189.0: se ignora. Ver importeTotal",
				example = "2400.00", deprecated = true,
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
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
