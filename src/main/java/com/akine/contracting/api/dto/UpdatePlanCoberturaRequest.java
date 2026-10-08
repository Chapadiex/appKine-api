package com.akine.contracting.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Edicion parcial de un plan, incluido el CIERRE DE VIGENCIA (RF-M15-005).
 *
 * <p>Los campos en {@code null} NO se tocan. Ni el codigo ni el financiador estan: son la
 * identidad del plan y las coberturas ya firmadas la referencian.
 *
 * <p><b>Cerrar la vigencia es mandar {@code vigenciaHasta}, no una operacion aparte.</b> Cerrar la
 * vigencia NO es dar de baja: el plan queda ACTIVO y consultable y lo unico que cambia es que deja
 * de ofrecerse para selecciones posteriores a esa fecha. Es el caso borde "plan sin nuevas altas
 * pero con pacientes vigentes".
 */
@Schema(description = "Campos editables de un plan. Los ausentes no se tocan")
public record UpdatePlanCoberturaRequest(

		@Schema(description = "Nuevo nombre. Unico entre los vigentes de ese financiador",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 160, message = "El nombre no puede superar los 160 caracteres")
		String nombre,

		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(max = 500, message = "La descripcion no puede superar los 500 caracteres")
		String descripcion,

		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaDesde,

		@Schema(
				description = "Ultimo dia INCLUSIVE. Mandarlo es CERRAR LA VIGENCIA: el plan sigue "
						+ "ACTIVO y deja de ofrecerse despues de esa fecha",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		LocalDate vigenciaHasta,

		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereAutorizacion,

		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		Boolean requiereCredencial,

		@Schema(description = "Nuevo copago. Viaja siempre con moneda",
				requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@DecimalMin(value = "0.00", message = "El copago no puede ser negativo")
		@Digits(integer = 10, fraction = 2, message = "El copago admite 2 decimales")
		BigDecimal copago,

		@Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED)
		@Size(min = 3, max = 3, message = "La moneda se declara con su codigo ISO 4217 de 3 letras")
		String moneda,

		@Schema(
				description = "Version leida. Si no coincide con la vigente la edicion se rechaza "
						+ "con 409 concurrent-modification en vez de pisar el cambio de otro",
				example = "0",
				requiredMode = Schema.RequiredMode.REQUIRED)
		long expectedVersion) {
}
