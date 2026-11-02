package com.akine.billing.api.dto;

import com.akine.billing.domain.MedioDePago;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Registra la plata que el financiador pago contra el lote (RF-M21-007).
 *
 * <p><b>Es el unico punto de M21 que toca la caja</b> (RN-M21-002): el movimiento se asienta en la
 * misma transaccion. Y no salda ninguna obligacion: eso pasa al conciliar.
 */
@Schema(
		name = "RegistrarPagoDeFinanciador",
		description = "Asienta un pago recibido. **Genera un movimiento de caja** en la misma "
				+ "transaccion; con transferencia o tarjeta ese movimiento no afecta el arqueo, "
				+ "porque esa plata nunca toco el cajon.")
public record RegistrarPagoFinanciadorRequest(

		@Schema(
				description = "Lo recibido. **Decimal exacto, nunca float.** Un pago mayor que el "
						+ "saldo del lote se rechaza: no esta pagando este lote.",
				example = "85000.00")
		@NotNull(message = "El importe es obligatorio")
		@DecimalMin(value = "0.01", message = "El importe tiene que ser mayor que cero") BigDecimal importe,

		@Schema(
				description = "Como llego. Casi siempre `TRANSFERENCIA`. Con `EFECTIVO` hace falta "
						+ "una jornada de caja abierta en la sede.",
				example = "TRANSFERENCIA")
		@NotNull(message = "El medio de pago es obligatorio") MedioDePago medio,

		@Schema(
				description = "Cuando pago el financiador, que puede no ser cuando se carga",
				example = "2026-09-18")
		@NotNull(message = "La fecha de pago es obligatoria") LocalDate fechaPago,

		@Schema(
				description = "Numero de transferencia o de recibo del financiador",
				example = "TRF-4471029")
		@Size(max = 80, message = "La referencia no puede superar los 80 caracteres") String referencia,

		@Schema(
				description = "Clave del cliente para que un doble click no cobre dos veces la "
						+ "misma transferencia. Ausente la desactiva.",
				example = "1d5b9a6e-0f2c-4b6f-9a1e-77c0b6b2f001")
		@Size(max = 80, message = "La clave de idempotencia no puede superar los 80 caracteres") String idempotencyKey) {
}
