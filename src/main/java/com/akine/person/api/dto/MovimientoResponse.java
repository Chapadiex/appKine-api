package com.akine.person.api.dto;

import com.akine.person.application.MovimientoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un hecho del ledger de una autorizacion, tal como sale de la API (RF-M17-004, RF-M17-005).
 *
 * <p><b>Append-only:</b> no hay endpoint que edite ni borre un movimiento, y no es un olvido. Un
 * ledger que se puede editar no es un ledger. Corregir un consumo se hace creando una
 * {@code REVERSION} que lo compensa, y el consumo original queda donde estaba diciendo que
 * ocurrio.
 */
@Schema(description = "Un movimiento del ledger de saldo de una autorizacion")
public record MovimientoResponse(

		@Schema(description = "Identificador del movimiento", example = "9001")
		long id,

		@Schema(description = "Autorizacion cuyo saldo mueve", example = "77")
		long autorizacionId,

		@Schema(description = "Paciente", example = "1204")
		long personaId,

		@Schema(description = "Sede donde ocurrio el hecho. Null admitido", example = "7")
		Long consultorioId,

		@Schema(
				description = "Que clase de hecho es. CONSUMO y REVERSION son los unicos que el "
						+ "sistema produce hoy; RESERVA y LIBERACION_DE_RESERVA existen en el "
						+ "modelo y ningun camino las emite, porque DP-05 prohibe consumir al "
						+ "reservar un turno",
				example = "CONSUMO",
				allowableValues = {"CONSUMO", "REVERSION", "RESERVA", "LIBERACION_DE_RESERVA"})
		String tipo,

		@Schema(description = "Unidades que mueve. SIEMPRE POSITIVA: el signo lo da el tipo",
				example = "1")
		int cantidad,

		@Schema(description = "Lo que este hecho le suma o le resta al saldo. Negativo si lo gasta",
				example = "-1")
		int efectoSobreElSaldo,

		@Schema(description = "Que clase de hecho lo produjo", example = "SESION",
				allowableValues = {"SESION", "MANUAL"})
		String tipoOrigen,

		@Schema(description = "Id de ese hecho: la sesion cerrada, tipicamente", example = "3312")
		long referenciaOrigen,

		@Schema(description = "Por que se revirtio. OBLIGATORIO en REVERSION, null en el resto")
		String motivo,

		@Schema(description = "Movimiento que esta fila compensa, si es una REVERSION",
				example = "9001")
		Long movimientoOrigenId,

		@Schema(
				description = "Instante UTC del HECHO, no de la insercion: para un CONSUMO es el "
						+ "cierre de la sesion",
				example = "2026-09-19T14:32:10.123456Z")
		Instant ocurrioEn,

		@Schema(description = "Cuenta que lo produjo. Null solo si no hubo actor humano",
				example = "42")
		Long actorCuentaId) {

	public static MovimientoResponse de(MovimientoView view) {
		return new MovimientoResponse(
				view.id(),
				view.autorizacionId(),
				view.personaId(),
				view.consultorioId(),
				view.tipo(),
				view.cantidad(),
				view.efectoSobreElSaldo(),
				view.tipoOrigen(),
				view.referenciaOrigen(),
				view.motivo(),
				view.movimientoOrigenId(),
				view.ocurrioEn(),
				view.actorCuentaId());
	}
}
