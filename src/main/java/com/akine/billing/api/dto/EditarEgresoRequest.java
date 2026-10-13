package com.akine.billing.api.dto;

import com.akine.billing.application.EgresoCommand;
import com.akine.billing.domain.CategoriaEgreso;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Correccion de un egreso en {@code BORRADOR} (RF-M22-001).
 *
 * <p>Antes del contrato 0.91.0 la correccion reusaba {@link RegistrarEgresoRequest} y no pedia
 * {@code version}: dos administrativos editando el mismo borrador se pisaban y ganaba el ultimo,
 * sin 409. Este cuerpo lleva solo lo que la correccion efectivamente cambia —el beneficiario, la
 * moneda y la clave de idempotencia no se editan— mas la version leida.
 */
@Schema(
		name = "EditarEgreso",
		description = "Correccion del borrador. Lleva la `version` que el cliente leyo: si otro la "
				+ "cambio antes, 409 `concurrent-modification`.")
public record EditarEgresoRequest(

		@Schema(description = "En que se fue la plata. Filtra y totaliza (RF-M22-004).",
				example = "HONORARIOS_PROFESIONALES")
		@NotNull CategoriaEgreso categoria,

		@Schema(description = "Periodo liquidado. Los dos o ninguno.", example = "2026-09-01")
		LocalDate periodoDesde,

		@Schema(example = "2026-09-30")
		LocalDate periodoHasta,

		@Schema(example = "Honorarios de septiembre 2026")
		@NotBlank @Size(max = 280) String concepto,

		@Schema(description = "Decimal exacto, nunca float.", example = "185000.00")
		@NotNull @DecimalMin(value = "0.01") BigDecimal importeTotal,

		@Schema(
				description = "Tipo de comprobante. **Opcional en borrador y obligatorio para "
						+ "confirmar**.",
				example = "FACTURA_C")
		@Size(max = 24) String comprobanteTipo,

		@Schema(
				description = "El mismo comprobante del mismo beneficiario no se carga dos veces: "
						+ "409 `egreso-comprobante-duplicado`.",
				example = "0001-00000123")
		@Size(max = 40) String comprobanteNumero,

		@Schema(example = "2026-10-03")
		LocalDate comprobanteFecha,

		@Schema(description = "Version que el cliente leyo. **Requerida.**", example = "0")
		@NotNull @PositiveOrZero Long version) {

	/** Solo los campos editables: el servicio no lee beneficiario, moneda ni clave al corregir. */
	public EgresoCommand aDominio() {
		return new EgresoCommand(
				categoria, null, null, null, null,
				periodoDesde, periodoHasta, concepto, importeTotal, null,
				comprobanteTipo, comprobanteNumero, comprobanteFecha, null);
	}
}
