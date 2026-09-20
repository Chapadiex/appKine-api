package com.akine.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;
import java.util.List;

/**
 * Apertura del borrador de un lote (RF-M21-002).
 *
 * <p><b>El numero no se pide:</b> lo asigna el servidor al confirmar, con un correlativo por sede y
 * financiador. Dejar que el cliente lo elija habilitaria numeros repetidos o huecos que despues
 * nadie puede explicar.
 */
@Schema(
		name = "CrearPresentacion",
		description = "Abre el borrador de un lote para reclamarle prestaciones a un financiador. "
				+ "**Crear no reclama nada**: hasta que se confirma, el lote no existe para el "
				+ "financiador.")
public record CrearPresentacionRequest(

		@Schema(description = "A quien se le va a reclamar", example = "31")
		@NotNull Long financiadorId,

		@Schema(
				description = "Primer dia del periodo que el lote abarca. Es un filtro declarado: "
						+ "dos lotes del mismo periodo son legitimos (uno complementario, un "
						+ "reenvio de rechazados).",
				example = "2026-08-01")
		@NotNull LocalDate periodoDesde,

		@Schema(description = "Ultimo dia del periodo, INCLUSIVE", example = "2026-08-31")
		@NotNull LocalDate periodoHasta,

		@Schema(
				description = "Moneda del lote, ISO 4217. Se fija al crear: un total que suma "
						+ "pesos con dolares no significa nada.",
				example = "ARS")
		@NotNull @Pattern(regexp = "^[A-Z]{3}$") String moneda,

		@Schema(
				description = "Las prestaciones con las que arranca. Puede venir vacio: armar el "
						+ "lote a mano, una por una, es un camino legitimo.",
				example = "[9001, 9002]")
		List<Long> obligacionIds) {

	/** Nunca {@code null} hacia adentro: un lote sin items es un borrador vacio, no un error. */
	public List<Long> obligacionIds() {
		return obligacionIds == null ? List.of() : obligacionIds;
	}
}
