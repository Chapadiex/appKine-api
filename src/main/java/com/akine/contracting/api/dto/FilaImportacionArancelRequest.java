package com.akine.contracting.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Una fila de la planilla de aranceles, ya parseada por el frontend (B-7, RF-M16-007).
 *
 * <p><b>Sin Bean Validation a proposito.</b> Un {@code @NotNull} aca haria fallar el lote entero con
 * un 400 por un campo vacio en la fila 87, y el RF pide errores POR FILA. Cada fila se valida en el
 * servicio con las reglas del alta unitaria y su rechazo viaja en la respuesta.
 */
@Schema(description = "Una fila de la importacion: el arancel de una practica, opcionalmente dentro "
		+ "de una oferta")
public record FilaImportacionArancelRequest(

		@Schema(description = "Practica del catalogo. Si se omite, se resuelve por codigoPractica",
				example = "412", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Long practicaId,

		@Schema(description = "Codigo de la practica, tal como lo trae la planilla del financiador. "
				+ "Se busca entre las practicas vigentes del tenant (globales y propias) y tiene que "
				+ "resolver a una sola. Si viene junto con practicaId, tiene que ser el suyo",
				example = "25.01.01", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		String codigoPractica,

		@Schema(description = "Opcional (RF-M16-008). Con oferta, el arancel es el de la practica "
				+ "dentro de esa oferta; sin ella, el general", example = "77",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Long ofertaId,

		@Schema(description = "Lo que vale la practica bajo el convenio. Hasta 2 decimales",
				example = "12000.00")
		BigDecimal importeTotal,

		@Schema(description = "La parte del financiador. Con el coseguro tiene que sumar "
				+ "EXACTAMENTE el importe total", example = "9600.00")
		BigDecimal importeFinanciador,

		@Schema(description = "La parte del paciente", example = "2400.00")
		BigDecimal coseguro,

		@Schema(description = "Primer dia en que el importe se aplica", example = "2027-01-01")
		LocalDate vigenciaDesde,

		@Schema(description = "Ultimo dia en que se aplica, INCLUSIVE. Null = sin fin previsto. "
				+ "Tiene que estar contenida en la vigencia del convenio",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta) {
}
