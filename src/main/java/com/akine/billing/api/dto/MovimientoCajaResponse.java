package com.akine.billing.api.dto;

import com.akine.billing.application.MovimientoCajaView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Un movimiento monetario real del ledger de caja.
 *
 * <p>El ledger es <b>append-only</b>: este recurso no tiene PUT ni DELETE y no los va a tener. Un
 * error se compensa con otro movimiento, y {@code movimientoOrigenId} es lo que los liga
 * (RF-M24-006).
 */
@Schema(
		name = "MovimientoDeCaja",
		description = "Entrada o salida de dinero real. Append-only: se compensa, no se edita.")
public record MovimientoCajaResponse(

		@Schema(example = "9001")
		long id,

		@Schema(
				description = "`null` cuando la plata nunca toco el cajon y no habia caja abierta "
						+ "—un cobro con tarjeta, por ejemplo—.",
				example = "3001")
		Long jornadaCajaId,

		@Schema(example = "2026-09-20")
		LocalDate fechaNegocio,

		@Schema(
				description = "El signo lo da el tipo, **no el importe**, que es siempre positivo.",
				allowableValues = {"INGRESO", "EGRESO", "REVERSION_DE_INGRESO", "REVERSION_DE_EGRESO"},
				example = "INGRESO")
		String tipo,

		@Schema(allowableValues = {"EFECTIVO", "TRANSFERENCIA", "TARJETA_DEBITO", "TARJETA_CREDITO", "OTRO"})
		String medio,

		@Schema(description = "Siempre positivo.", example = "8500.00")
		BigDecimal importe,

		@Schema(example = "ARS")
		String moneda,

		@Schema(
				description = "Si mueve el saldo que se cuenta al arquear. **Solo el efectivo.** Un "
						+ "arqueo es contar billetes; una tarjeta liquida a 18 dias.",
				example = "true")
		boolean afectaArqueo,

		@Schema(example = "Cobro 5001")
		String concepto,

		@Schema(description = "Obligatorio en las reversiones.")
		String motivo,

		@Schema(
				description = "Que produjo el movimiento.",
				allowableValues = {"COBRO", "MANUAL", "REVERSION"},
				example = "COBRO")
		String tipoOrigen,

		@Schema(
				description = "Id del hecho dentro de su origen: el cobro, o el movimiento revertido.",
				example = "5001")
		Long referenciaOrigen,

		@Schema(
				description = "El movimiento que este compensa (RF-M24-006). **Puede pertenecer a una "
						+ "jornada distinta y ya cerrada**: la compensacion cae en la jornada abierta "
						+ "hoy, porque la plata se mueve hoy y porque reescribir un arqueo cerrado "
						+ "borraria la evidencia de su diferencia.",
				example = "8800")
		Long movimientoOrigenId,

		@Schema(example = "2026-09-20T13:02:00Z")
		Instant registradoEn,

		@Schema(example = "44")
		long registradoPorCuentaId) {

	public static MovimientoCajaResponse de(MovimientoCajaView vista) {
		return new MovimientoCajaResponse(
				vista.id(), vista.jornadaCajaId(), vista.fechaNegocio(), vista.tipo(),
				vista.medio(), vista.importe(), vista.moneda(), vista.afectaArqueo(),
				vista.concepto(), vista.motivo(), vista.tipoOrigen(), vista.referenciaOrigen(),
				vista.movimientoOrigenId(), vista.registradoEn(), vista.registradoPorCuentaId());
	}
}
