package com.akine.person.api.dto;

import com.akine.person.application.AlertaDeAutorizacionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Una alerta sobre una autorizacion, tal como sale de la API (DP-13, RN-M17-003, AKINE C-4).
 *
 * <p><b>No mueve el saldo.</b> Dice que hay un consumo que conviene mirar; si corresponde
 * devolver la unidad, eso se hace revirtiendo el consumo (RF-M17-005), que ademas resuelve la
 * alerta.
 */
@Schema(description = "Una alerta sobre una autorizacion que nace de un hecho de otro modulo")
public record AlertaDeAutorizacionResponse(

		@Schema(description = "Identificador de la alerta", example = "31")
		long id,

		@Schema(description = "Autorizacion sobre la que se alerta", example = "77")
		long autorizacionId,

		@Schema(
				description = "Que clase de alerta es. CONSUMO_A_REVISAR: se anulo la obligacion "
						+ "de una sesion que consumio esta autorizacion (DP-13)",
				example = "CONSUMO_A_REVISAR",
				allowableValues = {"CONSUMO_A_REVISAR"})
		String tipo,

		@Schema(description = "El movimiento CONSUMO del ledger que hay que revisar", example = "9001")
		long movimientoId,

		@Schema(description = "Sesion que consumio", example = "3312")
		long sesionId,

		@Schema(description = "Primera obligacion cuya anulacion genero la alerta", example = "5120")
		long obligacionId,

		@Schema(description = "Motivo con que se anulo la obligacion. Null si no se declaro")
		String motivoOrigen,

		@Schema(description = "Instante UTC de la anulacion que la genero",
				example = "2026-10-06T14:32:10.123456Z")
		Instant generadaEn,

		@Schema(description = "Cuenta que anulo la obligacion", example = "42")
		Long generadaPor,

		@Schema(description = "Sin resolver todavia", example = "true")
		boolean pendiente,

		@Schema(description = "Como se resolvio. REVERTIDO: se revirtio el consumo. Null si "
				+ "esta pendiente", example = "REVERTIDO", allowableValues = {"REVERTIDO"})
		String resolucion,

		@Schema(description = "Instante UTC de la resolucion. Null si esta pendiente")
		Instant resueltaEn,

		@Schema(description = "Cuenta que la resolvio. Null si esta pendiente", example = "42")
		Long resueltaPor) {

	public static AlertaDeAutorizacionResponse de(AlertaDeAutorizacionView view) {
		return new AlertaDeAutorizacionResponse(
				view.id(),
				view.autorizacionId(),
				view.tipo(),
				view.movimientoId(),
				view.sesionId(),
				view.obligacionId(),
				view.motivoOrigen(),
				view.generadaEn(),
				view.generadaPor(),
				view.pendiente(),
				view.resolucion(),
				view.resueltaEn(),
				view.resueltaPor());
	}
}
