package com.akine.person.api.dto;

import com.akine.person.application.EventoDeAutorizacionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un hecho del historial de una autorizacion (DP-23, AKINE B-4).
 *
 * <p><b>Append-only:</b> no hay endpoint que edite ni borre un evento. El vencimiento no es un
 * evento: viaja calculado en {@link HistorialDeAutorizacionResponse}.
 */
@Schema(description = "Un hecho del historial de estados de una autorizacion")
public record EventoDeAutorizacionResponse(

		@Schema(description = "Identificador del evento", example = "3101")
		long id,

		@Schema(description = "Que paso",
				example = "APROBACION",
				allowableValues = {"ALTA", "APROBACION", "OBSERVACION", "RECHAZO", "MODIFICACION",
						"DOCUMENTO", "CONSUMO", "REVERSION_DE_CONSUMO", "ANULACION"})
		String tipo,

		@Schema(description = "Estado antes del hecho. Null solo en el ALTA",
				example = "PENDIENTE",
				allowableValues = {"PENDIENTE", "APROBADA", "OBSERVADA", "RECHAZADA"})
		String estadoAnterior,

		@Schema(description = "Estado despues del hecho. Igual al anterior cuando el hecho no "
				+ "resuelve nada (modificacion, documento, consumo, reversion, anulacion)",
				example = "APROBADA",
				allowableValues = {"PENDIENTE", "APROBADA", "OBSERVADA", "RECHAZADA"})
		String estadoNuevo,

		@Schema(description = "Ciclo de vida despues del hecho: false a partir de la ANULACION")
		boolean activa,

		@Schema(description = "Unidades consumidas o devueltas. Solo CONSUMO y "
				+ "REVERSION_DE_CONSUMO", example = "1")
		Integer cantidad,

		@Schema(description = "Movimiento del ledger que lo produjo. Solo CONSUMO y "
				+ "REVERSION_DE_CONSUMO. Se lee en GET /api/v1/autorizaciones/{id}/movimientos",
				example = "9001")
		Long movimientoId,

		@Schema(description = "Que cambio, en terminos administrativos: cantidades, fechas, "
				+ "orden, comprobante o sesion. Sin datos clinicos",
				example = "cantidadAutorizada: 10 -> 8; vigenciaHasta: 2026-12-31 -> 2026-11-30")
		String detalle,

		@Schema(description = "Motivo declarado: observar, rechazar, anular o revertir")
		String motivo,

		@Schema(description = "Sede desde la que se hizo", example = "7")
		Long consultorioId,

		@Schema(description = "Cuenta que lo hizo. Null si lo hizo el sistema", example = "15")
		Long actorCuentaId,

		@Schema(description = "Instante del hecho", example = "2026-10-08T14:03:11Z")
		Instant ocurridoEn) {

	public static EventoDeAutorizacionResponse de(EventoDeAutorizacionView vista) {
		return new EventoDeAutorizacionResponse(
				vista.id(),
				vista.tipo(),
				vista.estadoAnterior(),
				vista.estadoNuevo(),
				vista.activa(),
				vista.cantidad(),
				vista.movimientoId(),
				vista.detalle(),
				vista.motivo(),
				vista.consultorioId(),
				vista.actorCuentaId(),
				vista.ocurridoEn());
	}
}
