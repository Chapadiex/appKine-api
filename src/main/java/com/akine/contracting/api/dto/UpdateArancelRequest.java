package com.akine.contracting.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Edicion parcial de un arancel.
 *
 * <p>Lo que llega en {@code null} no se toca, <b>pero los importes se validan como terna</b>: subir
 * el total sin tocar las partes rompe la invariante de que sumen, y se rechaza con 400.
 *
 * <p>Ni la practica ni el convenio estan aca: cambiarlos no seria editar este arancel, seria
 * inventar otro.
 *
 * <p><b>La forma correcta de subir un precio no es esta operacion</b>, es cerrar la vigencia del
 * arancel actual y crear otro. Editar el importe de una ventana ya transcurrida se admite —a veces
 * hay que corregir una carga— y no reescribe nada de lo ya liquidado, porque eso guardo su propio
 * snapshot congelado (RN-M16-003).
 */
@Schema(description = "Cambios a aplicar sobre un arancel. Los campos nulos no se tocan")
public record UpdateArancelRequest(

		@Schema(description = "Lo que vale la practica bajo este convenio", example = "13000.00")
		@DecimalMin(value = "0.00", message = "El importe total no puede ser negativo")
		@Digits(integer = 10, fraction = 2, message = "Los importes admiten 2 decimales")
		BigDecimal importeTotal,

		@Schema(description = "La parte que paga el financiador", example = "10400.00")
		@DecimalMin(value = "0.00", message = "La parte del financiador no puede ser negativa")
		@Digits(integer = 10, fraction = 2, message = "Los importes admiten 2 decimales")
		BigDecimal importeFinanciador,

		@Schema(description = "La parte que paga el paciente", example = "2600.00")
		@DecimalMin(value = "0.00", message = "El coseguro no puede ser negativo")
		@Digits(integer = 10, fraction = 2, message = "Los importes admiten 2 decimales")
		BigDecimal coseguro,

		@Schema(description = "Primer dia en que este importe se aplica", example = "2026-01-01")
		LocalDate vigenciaDesde,

		@Schema(description = "Ultimo dia en que se aplica, INCLUSIVE", example = "2026-06-30")
		LocalDate vigenciaHasta,

		@Schema(description = "Version leida del arancel", example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@PositiveOrZero(message = "La version no puede ser negativa")
		long expectedVersion) {
}
