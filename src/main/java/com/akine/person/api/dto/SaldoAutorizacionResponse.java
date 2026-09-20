package com.akine.person.api.dto;

import com.akine.person.application.SaldoDeAutorizacionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Cuanto queda en una autorizacion, por las DOS fuentes (RF-M17-003).
 *
 * <p>{@code saldo} sale de la columna materializada y {@code saldoSegunElLedger} se recalcula
 * sumando los movimientos. <b>Tienen que dar lo mismo</b>, y {@code coherente} dice si dan. Es la
 * unica mitigacion que esta version tiene al riesgo declarado de que el {@code UPDATE} del saldo y
 * el {@code INSERT} del movimiento diverjan: nadie los reconcilia automaticamente.
 *
 * <p>Esta consulta <b>no corrige</b>. Si divergen, lo dice y nada mas: reescribir la columna en un
 * GET seria una mutacion escondida, y taparia el sintoma antes de que nadie pueda averiguar que
 * camino la produjo.
 */
@Schema(description = "Saldo de una autorizacion, por la columna y por el ledger")
public record SaldoAutorizacionResponse(

		@Schema(description = "Identificador de la autorizacion", example = "77")
		long autorizacionId,

		@Schema(description = "Paciente", example = "1204")
		long personaId,

		@Schema(description = "Dia contra el que se evaluo vigencia y vencimiento",
				example = "2026-09-19")
		LocalDate fecha,

		@Schema(description = "Lo que el financiador otorgo. Null = sin tope declarado",
				example = "10")
		Integer cantidadAutorizada,

		@Schema(description = "Consumidas segun la columna materializada", example = "4")
		int cantidadConsumida,

		@Schema(description = "Consumidas recalculadas desde el ledger. Tiene que coincidir",
				example = "4")
		int consumidaSegunElLedger,

		@Schema(description = "Restantes segun la columna. Null si no hay tope", example = "6")
		Integer saldo,

		@Schema(description = "Restantes segun el ledger. Null si no hay tope", example = "6")
		Integer saldoSegunElLedger,

		@Schema(
				description = "Las dos cuentas coinciden. FALSE es un problema de coherencia que "
						+ "hay que investigar, no un estado normal",
				example = "true")
		boolean coherente,

		@Schema(description = "La vigencia cubre ese dia", example = "true")
		boolean vigente,

		@Schema(description = "Tiene fin declarado y ya paso", example = "false")
		boolean vencida,

		@Schema(description = "Sin saldo. Una sin tope declarado nunca esta agotada",
				example = "false")
		boolean agotada,

		@Schema(
				description = "El veredicto completo: activa, APROBADA, vigente y con saldo. "
						+ "Consultarlo NO consume nada",
				example = "true")
		boolean habilita,

		@Schema(description = "Dias que faltan para vencer. Null si no vence", example = "72")
		Long diasParaVencer,

		@Schema(description = "Cuantos hechos hay en el ledger", example = "5")
		int movimientos) {

	public static SaldoAutorizacionResponse de(SaldoDeAutorizacionView view) {
		return new SaldoAutorizacionResponse(
				view.autorizacionId(),
				view.personaId(),
				view.fecha(),
				view.cantidadAutorizada(),
				view.cantidadConsumida(),
				view.consumidaSegunElLedger(),
				view.saldo(),
				view.saldoSegunElLedger(),
				view.coherente(),
				view.vigente(),
				view.vencida(),
				view.agotada(),
				view.habilita(),
				view.diasParaVencer(),
				view.movimientos());
	}
}
