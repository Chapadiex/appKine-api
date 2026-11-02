package com.akine.billing.api.dto;

import com.akine.billing.application.MovimientoManualCommand;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.TipoMovimiento;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Ingreso o egreso cargado a mano (RF-M20-002, RF-M20-003).
 *
 * <p><b>Las reversiones no entran por aca</b>: tienen su propia operacion porque necesitan el
 * movimiento que compensan, y porque una anulacion que se pueda escribir como si fuera un
 * movimiento nuevo deja de ser trazable hasta su origen.
 */
@Schema(
		name = "RegistrarMovimientoDeCaja",
		description = "Movimiento monetario real cargado por una persona. El importe es **siempre "
				+ "positivo**: el signo lo da el tipo.")
public record RegistrarMovimientoRequest(

		@Schema(
				description = "`INGRESO` o `EGRESO`. Una reversion se pide en su propia operacion.",
				allowableValues = {"INGRESO", "EGRESO"},
				example = "EGRESO")
		@NotNull(message = "El tipo de movimiento es obligatorio") TipoMovimiento tipo,

		@Schema(
				description = "Por que via se movio. **Solo `EFECTIVO` afecta el arqueo**: un arqueo "
						+ "es contar billetes, y una tarjeta liquida a 18 dias a una cuenta que este "
						+ "modulo no modela. Los demas medios se registran y se totalizan igual, "
						+ "para que la operatoria del dia este completa.",
				allowableValues = {"EFECTIVO", "TRANSFERENCIA", "TARJETA_DEBITO", "TARJETA_CREDITO", "OTRO"},
				example = "EFECTIVO")
		@NotNull(message = "El medio de pago es obligatorio") MedioDePago medio,

		@Schema(description = "Siempre positivo. Decimal exacto, nunca float.", example = "2500.00")
		@NotNull(message = "El importe es obligatorio")
		@DecimalMin(value = "0.01", message = "El importe tiene que ser mayor que cero") BigDecimal importe,

		@Schema(
				description = "Que fue. **Obligatorio**: plata que aparece o desaparece del cajon sin "
						+ "explicacion es exactamente lo que una auditoria busca.",
				example = "Compra de insumos de limpieza")
		@NotBlank(message = "El concepto es obligatorio")
		@Size(max = 160, message = "El concepto no puede superar los 160 caracteres") String concepto,

		@Schema(
				description = "Clave del cliente para que un doble click no cargue dos veces el mismo "
						+ "movimiento. Reusarla con **otro** contenido devuelve 409.",
				example = "1f0c6f9e-2a77-4e0f-9a51-3c2b8d7e4a10")
		@Size(max = 80, message = "La clave de idempotencia no puede superar los 80 caracteres") String idempotencyKey) {

	public MovimientoManualCommand aDominio() {
		return new MovimientoManualCommand(tipo, medio, importe, concepto, idempotencyKey);
	}
}
