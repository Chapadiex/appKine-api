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

		@Schema(description = "Lo que impide confirmar. Vacio cuando el lote esta listo")
		List<Reparo> hallazgos,

		@Schema(description = "Lo que conviene regularizar y NO impide confirmar (hoy, "
				+ "`CREDENCIAL_FALTANTE`). Misma forma que `hallazgos`")
		List<Reparo> avisos) {

	/** Un problema concreto sobre una prestacion concreta. */
	@Schema(name = "ReparoDePresentacion")
	public record Reparo(

			@Schema(example = "55010")
			long itemId,

			@Schema(example = "9001")
			long obligacionId,

			@Schema(
					description = "Los requisitos documentales se evaluan contra lo que el "
							+ "convenio exigia el dia de la prestacion: `SIN_ORDEN` y "
							+ "`SIN_AUTORIZACION` van en `hallazgos` (bloquean); "
							+ "`CREDENCIAL_FALTANTE` va en `avisos` (no bloquea).",
					allowableValues = {
							"OBLIGACION_ANULADA", "SIN_SALDO", "DEUDA_DEL_PACIENTE",
							"FINANCIADOR_DISTINTO",
							"SEDE_DISTINTA", "FUERA_DEL_PERIODO", "MONEDA_DISTINTA",
							"SIN_ORDEN", "SIN_AUTORIZACION", "CREDENCIAL_FALTANTE"},
					example = "FUERA_DEL_PERIODO")
			String hallazgo) {
	}

	public static ValidacionPresentacionResponse de(ValidacionDePresentacion validacion) {
		return new ValidacionPresentacionResponse(
				validacion.presentacionId(),
				validacion.confirmable(),
				reparos(validacion.hallazgos()),
				reparos(validacion.avisos()));
	}

	private static List<Reparo> reparos(List<ValidacionDePresentacion.Reparo> reparos) {
		return reparos.stream()
				.map(reparo -> new Reparo(
						reparo.itemId(), reparo.obligacionId(), reparo.hallazgo().name()))
				.toList();
	}
}
