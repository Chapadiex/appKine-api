package com.akine.organization.api.dto;

import com.akine.organization.application.LimitUsageView;
import com.akine.organization.spi.LimitCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Consumo actual de un limite del plan contratado.
 *
 * <p>Es el unico dato "interno" que los errores 409 de limite si publican, y a proposito: sin
 * el limite y el uso actual el cliente no puede decirle al usuario que le falta, ni ofrecerle
 * un upgrade con sentido. No revela nada de otros tenants ni de la implementacion.
 */
@Schema(description = "Consumo actual de un limite del plan contratado")
public record LimitUsageResponse(

		@Schema(description = "Codigo del limite", example = "MAX_CONSULTORIOS")
		LimitCode code,

		@Schema(
				description = "Tope que impone el plan. Ausente cuando el limite es ilimitado",
				example = "3")
		Integer limitValue,

		@Schema(description = "Cantidad de recursos activos que consumen este limite", example = "2")
		long currentUsage,

		@Schema(
				description = "true cuando el uso actual ya supera el tope del plan. Un downgrade "
						+ "puede dejarlo en true sin borrar nada: lo existente sigue operativo y "
						+ "solo se rechazan las altas nuevas",
				example = "false")
		boolean exceeded) {

	public static LimitUsageResponse from(LimitUsageView view) {
		return new LimitUsageResponse(
				view.code(), view.limitValue(), view.currentUsage(), view.exceeded());
	}

	public static List<LimitUsageResponse> fromAll(List<LimitUsageView> views) {
		return views.stream().map(LimitUsageResponse::from).toList();
	}
}
