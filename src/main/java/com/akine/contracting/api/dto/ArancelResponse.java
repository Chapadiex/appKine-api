package com.akine.contracting.api.dto;

import com.akine.contracting.application.ArancelView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Un arancel de una practica bajo un convenio, con su vigencia (M16). */
@Schema(description = "Arancel de una practica bajo un convenio")
public record ArancelResponse(

		@Schema(description = "Identificador del arancel", example = "901")
		long id,

		@Schema(description = "Convenio al que pertenece. No cambia", example = "140")
		long convenioId,

		@Schema(description = "Practica arancelada. No cambia", example = "412")
		long practicaId,

		@Schema(description = "OBSOLETO desde 0.185.0: historico de aranceles anteriores; nulo en "
				+ "los nuevos", example = "12000.00", deprecated = true, nullable = true)
		BigDecimal importeTotal,

		@Schema(description = "Lo que paga el financiador por la practica (B)", example = "9600.00")
		BigDecimal importeFinanciador,

		@Schema(description = "OBSOLETO desde 0.185.0: historico; nulo en los nuevos. El coseguro "
				+ "es precio de la oferta menos importeFinanciador", example = "2400.00",
				deprecated = true, nullable = true)
		BigDecimal coseguro,

		@Schema(description = "ISO 4217, heredada del convenio", example = "ARS")
		String moneda,

		@Schema(description = "Primer dia en que este importe se aplica", example = "2026-01-01")
		LocalDate vigenciaDesde,

		@Schema(description = "Ultimo dia en que se aplica, INCLUSIVE. Null = sin fin previsto")
		LocalDate vigenciaHasta,

		@Schema(description = "Si la fecha consultada cae dentro de la vigencia. DISTINTO de estado")
		boolean vigente,

		@Schema(description = "Ciclo de vida", example = "ACTIVO",
				allowableValues = {"ACTIVO", "INACTIVO"})
		String estado,

		@Schema(description = "Instante UTC de la baja logica. Null mientras este vigente")
		Instant deletedAt,

		@Schema(description = "Motivo declarado de la baja. Null mientras este vigente")
		String deactivationReason,

		@Schema(description = "Version a reenviar para editar", example = "0")
		long version,

		@Schema(
				description = "Null = arancel GENERAL de la practica en el convenio. Con valor: arancel de "
						+ "la practica cuando se presta dentro de esa oferta (RF-M16-008), que al "
						+ "resolver con esa oferta manda sobre el general",
				example = "77")
		Long ofertaId) {

	public static ArancelResponse de(ArancelView view) {
		return new ArancelResponse(
				view.id(),
				view.convenioId(),
				view.practicaId(),
				view.importeTotal(),
				view.importeFinanciador(),
				view.coseguro(),
				view.moneda(),
				view.vigenciaDesde(),
				view.vigenciaHasta(),
				view.vigente(),
				view.estado(),
				view.deletedAt(),
				view.deactivationReason(),
				view.version(),
				view.ofertaId());
	}
}
