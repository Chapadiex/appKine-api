package com.akine.billing.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Arqueo y cierre de la jornada (RF-M20-006).
 *
 * <p><b>La diferencia no viaja en el cuerpo: la calcula el servidor</b> contra su propio saldo. Lo
 * que el cliente declara es lo que <b>conto</b>, que es un hecho del mundo fisico, y el teorico que
 * la pantalla mostraba cuando empezo a contar.
 */
@Schema(
		name = "CerrarCaja",
		description = "Cierra el turno de caja declarando lo que se conto. La diferencia la calcula "
				+ "el servidor y, si no es cero, exige motivo.")
public record CerrarCajaRequest(

		@Schema(
				description = "El saldo teorico que la pantalla mostraba **cuando se empezo a "
						+ "contar**. Si entraron movimientos mientras tanto, el cierre se rechaza "
						+ "con 409 `caja-saldo-cambio` en vez de registrar una diferencia que nunca "
						+ "existio. Es el control optimista del cierre.",
				example = "142500.00")
		@NotNull @DecimalMin(value = "0.00") BigDecimal saldoTeoricoEsperado,

		@Schema(
				description = "Lo que se conto fisicamente. **Decimal exacto, nunca float.**",
				example = "142500.00")
		@NotNull @DecimalMin(value = "0.00") BigDecimal saldoDeclarado,

		@Schema(
				description = "Por que no cuadra. **Obligatorio cuando hay diferencia** (RN-M20-004) "
						+ "y se ignora cuando la diferencia es cero: un motivo que a veces adorna un "
						+ "cierre correcto deja de leerse en los cierres que si importan.",
				example = "Faltan 500 del vuelto de la tarde")
		@Size(max = 280) String motivoDiferencia) {
}
