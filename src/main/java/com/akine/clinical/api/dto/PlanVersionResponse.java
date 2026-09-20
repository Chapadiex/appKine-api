package com.akine.clinical.api.dto;

import com.akine.clinical.application.PlanVersionView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Una version del plan con <b>sus</b> items, tal como sale por la API.
 *
 * <p>Los items viajan dentro de la version y no al lado del plan, y no es un detalle de
 * serializacion: es el modelo. Cada version tiene su propio juego de cantidades, asi que el avance
 * "8 de 20" de la version 1 y el "8 de 24" de la version 2 son dos numeros distintos y los dos
 * correctos. <b>La pantalla tiene que decir de que version habla.</b>
 */
@Schema(name = "PlanTratamientoVersion",
		description = "Contenido de una version de Plan de Tratamiento, con sus practicas")
public record PlanVersionResponse(

		@Schema(example = "210")
		long id,

		@Schema(description = "Orden de la version dentro del plan. La 1 es la original",
				example = "2")
		int numeroVersion,

		@Schema(description = "Objetivos terapeuticos de esta version. Es contenido clinico",
				example = "Recuperar flexion completa y retomar trote suave")
		String objetivos,

		@Schema(description = "Indicaciones generales. Ausente si no se cargaron")
		String indicaciones,

		@Schema(description = "Sesiones por semana PROPUESTAS. Es una sugerencia de recurrencia: "
				+ "el plan no agenda nada", example = "3")
		Integer frecuenciaSemanal,

		@Schema(description = "Duracion estimada en semanas. Completarla no finaliza el plan "
				+ "(RN-M11-004)", example = "8")
		Integer duracionSemanas,

		@Schema(description = "Por que se modifico. Ausente en la version 1, que no modifica nada")
		String motivoModificacion,

		@Schema(example = "2026-09-19T11:02:44Z")
		Instant registradaEn,

		@Schema(description = "Cuenta que escribio ESTA version. Quien modifica no suele ser quien "
				+ "escribio la original", example = "8")
		long registradaPor,

		@Schema(description = "Practicas planificadas en esta version. NO traen realizadas: eso es "
				+ "el avance, que se deriva")
		List<PlanItemResponse> items) {

	public static PlanVersionResponse from(PlanVersionView view) {
		return new PlanVersionResponse(
				view.id(),
				view.numeroVersion(),
				view.objetivos(),
				view.indicaciones(),
				view.frecuenciaSemanal(),
				view.duracionSemanas(),
				view.motivoModificacion(),
				view.registradaEn(),
				view.registradaPor(),
				view.items().stream().map(PlanItemResponse::from).toList());
	}
}
