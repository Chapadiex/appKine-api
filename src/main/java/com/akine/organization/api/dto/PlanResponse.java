package com.akine.organization.api.dto;

import com.akine.organization.application.PlanView;
import com.akine.organization.spi.FeatureCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Un plan del catalogo comercial, con lo que incluye.
 *
 * <p>No se expone el id interno del plan: el catalogo se referencia siempre por {@code code},
 * que es estable, legible en soporte y el unico identificador que los endpoints de contratacion
 * aceptan. Publicar el id invitaria a que el frontend lo guarde y quedaria acoplado a una clave
 * subrogada que no le pertenece.
 */
@Schema(description = "Plan del catalogo comercial con sus limites y funcionalidades")
public record PlanResponse(

		@Schema(description = "Codigo estable del plan, usado para contratarlo", example = "BASICO")
		String code,

		@Schema(description = "Nombre comercial del plan", example = "Basico")
		String name,

		@Schema(description = "Limites cuantitativos que impone el plan")
		List<PlanLimitResponse> limits,

		@Schema(description = "Funcionalidades habilitadas por el plan")
		List<FeatureCode> features) {

	public static PlanResponse from(PlanView view) {
		return new PlanResponse(
				view.code(),
				view.name(),
				view.limits().stream().map(PlanLimitResponse::from).toList(),
				view.features());
	}
}
