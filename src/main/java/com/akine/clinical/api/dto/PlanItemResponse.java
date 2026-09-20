package com.akine.clinical.api.dto;

import com.akine.clinical.application.PlanItemView;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Una practica planificada, tal como sale por la API.
 *
 * <p><b>No trae realizadas ni canceladas.</b> Eso es el avance, que es otra operacion
 * —{@code GET /planes-tratamiento/&#123;id&#125;/avance}— y se deriva contando sesiones cerradas del
 * Caso. Mezclarlos aca haria que abrir la ficha de un plan consultara sesiones, y que la ficha y el
 * avance pudieran contestar cosas distintas sin que se note cual de las dos vale.
 */
@Schema(name = "PlanItemPlanificado",
		description = "Practica planificada en una version del plan, con sus cantidades")
public record PlanItemResponse(

		@Schema(example = "301")
		long id,

		@Schema(description = "Oferta planificada", example = "42")
		long ofertaId,

		@Schema(description = "Sede de la OFERTA, no del plan. Las ofertas son por sede",
				example = "7")
		long ofertaConsultorioId,

		@Schema(description = "COPIA del nombre comercial al planificar. Un plan de seis meses se "
				+ "lee con los nombres que tenia al planificarse, no con los de hoy",
				example = "Kinesiologia individual 45 min")
		String ofertaNombre,

		@Schema(description = "COPIA del servicio que la oferta prestaba al planificar",
				example = "12")
		long servicioId,

		@Schema(description = "Sesiones que la decision clinica estima", example = "20")
		int cantidadPlanificada,

		@Schema(description = "Sesiones que la cobertura otorgo, declaradas a mano. Ausente = sin "
				+ "tope declarado, que es distinto de cero", example = "10")
		Integer cantidadAutorizada,

		@Schema(description = "DECLARADA hoy; AUTORIZACION cuando exista la integracion con M17",
				allowableValues = {"DECLARADA", "AUTORIZACION"}, example = "DECLARADA")
		String origenAutorizacion,

		@Schema(description = "Autorizacion de M17 que respalda la cantidad. Siempre ausente "
				+ "mientras el origen sea DECLARADA")
		Long autorizacionId) {

	public static PlanItemResponse from(PlanItemView view) {
		return new PlanItemResponse(
				view.id(),
				view.ofertaId(),
				view.ofertaConsultorioId(),
				view.ofertaNombre(),
				view.servicioId(),
				view.cantidadPlanificada(),
				view.cantidadAutorizada(),
				view.origenAutorizacion(),
				view.autorizacionId());
	}
}
