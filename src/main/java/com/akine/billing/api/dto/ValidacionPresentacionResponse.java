package com.akine.billing.api.dto;

import com.akine.billing.application.ValidacionDePresentacion;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * El resultado de validar un lote antes de confirmarlo (RF-M21-003).
 *
 * <p><b>Es una lectura: no cambia estado y no bloquea nada.</b> Lo que bloquea es confirmar, que la
 * vuelve a correr del lado del servidor — el frontend nunca es autoridad.
 */
@Schema(
		name = "ValidacionDePresentacion",
		description = "Las prestaciones del lote que no se pueden reclamar, con el motivo de cada "
				+ "una. La lista entera y no la primera: hay que poder arreglar todo de una vez.")
public record ValidacionPresentacionResponse(

		@Schema(example = "1204")
		long presentacionId,

		@Schema(
				description = "`true` cuando el lote tiene prestaciones y ninguna tiene reparos",
				example = "false")
		boolean confirmable,

		@Schema(description = "Vacio cuando el lote esta listo")
		List<Reparo> hallazgos) {

	/** Un problema concreto sobre una prestacion concreta. */
	@Schema(name = "ReparoDePresentacion")
	public record Reparo(

			@Schema(example = "55010")
			long itemId,

			@Schema(example = "9001")
			long obligacionId,

			@Schema(
					description = "Los tres requisitos documentales del convenio —orden, "
							+ "autorizacion y credencial— **todavia no se validan**: viven en el "
							+ "arancel congelado y el devengado no los copia a la obligacion.",
					allowableValues = {
							"OBLIGACION_ANULADA", "SIN_SALDO", "DEUDA_DEL_PACIENTE",
							"FINANCIADOR_DISTINTO",
							"SEDE_DISTINTA", "FUERA_DEL_PERIODO", "MONEDA_DISTINTA"},
					example = "FUERA_DEL_PERIODO")
			String hallazgo) {
	}

	public static ValidacionPresentacionResponse de(ValidacionDePresentacion validacion) {
		return new ValidacionPresentacionResponse(
				validacion.presentacionId(),
				validacion.confirmable(),
				validacion.hallazgos().stream()
						.map(reparo -> new Reparo(
								reparo.itemId(), reparo.obligacionId(), reparo.hallazgo().name()))
						.toList());
	}
}
