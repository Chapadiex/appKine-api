package com.akine.clinical.api.dto;

import com.akine.clinical.application.AvanceDeItemView;
import com.akine.clinical.application.AvanceDelPlanView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * El avance de un plan contra una version de su contenido (RF-M11-005).
 *
 * <p><b>Todo lo que hay aca es derivado.</b> Ninguna de estas cuentas sale de una columna: salen de
 * contar sesiones cerradas del Caso por oferta. Esa es la etapa entera en una frase — planificado
 * no es realizado, y lo realizado lo sabe el modulo de las sesiones.
 */
@Schema(name = "AvanceDelPlan",
		description = "Avance derivado de un Plan de Tratamiento contra una version de su contenido")
public record AvanceDelPlanResponse(

		@Schema(example = "77")
		long planTratamientoId,

		@Schema(example = "17")
		long casoClinicoId,

		@Schema(description = "Estado del plan al momento de consultar",
				allowableValues = {"BORRADOR", "ACTIVO", "SUSPENDIDO", "FINALIZADO"},
				example = "ACTIVO")
		String estado,

		@Schema(description = "Version contra la que se conto. VIAJA SIEMPRE, y por eso: los items "
				+ "cuelgan de la version, asi que \"8 de 20\" en la version 1 y \"8 de 24\" en la "
				+ "2 son los dos correctos. La pantalla tiene que decir de cual habla",
				example = "2")
		int numeroVersion,

		@Schema(description = "Avance de cada practica planificada en esa version")
		List<AvanceDeItemResponse> items,

		@Schema(description = "Todas las practicas alcanzaron su cantidad planificada. ES UN AVISO "
				+ "Y NADA MAS (RN-M11-004): no finaliza el plan, no cierra el caso y no dispara "
				+ "ninguna transicion. La decision es clinica. Un plan sin practicas nunca esta "
				+ "completo", example = "false")
		boolean completo) {

	public static AvanceDelPlanResponse from(AvanceDelPlanView view) {
		return new AvanceDelPlanResponse(
				view.planTratamientoId(),
				view.casoClinicoId(),
				view.estado(),
				view.numeroVersion(),
				view.items().stream().map(AvanceDeItemResponse::from).toList(),
				view.completo());
	}

	/**
	 * El avance de una practica.
	 *
	 * <p>Va anidado y no en su propio archivo porque no existe fuera de esto: no hay ninguna
	 * operacion que devuelva el avance de un item suelto, y publicarlo aparte sugeriria que si.
	 */
	@Schema(name = "AvanceDeItem",
			description = "Avance derivado de una practica planificada")
	public record AvanceDeItemResponse(

			@Schema(example = "42")
			long ofertaId,

			@Schema(description = "Nombre congelado al planificar",
					example = "Kinesiologia individual 45 min")
			String ofertaNombre,

			@Schema(description = "Lo que la decision clinica estimo", example = "20")
			int planificadas,

			@Schema(description = "Lo que la cobertura otorgo, declarado. Ausente = sin tope")
			Integer autorizadas,

			@Schema(description = "DERIVADO: sesiones cerradas con el paciente presente para esa "
					+ "oferta dentro del caso. No sale de ninguna columna", example = "8")
			int realizadas,

			@Schema(description = "DERIVADO: sesiones cerradas con el paciente AUSENTE. Se muestran "
					+ "aparte porque el profesional necesita ver por que el avance no avanza. No "
					+ "son turnos cancelados: un turno cancelado no prueba ni desmiente que hubo "
					+ "atencion", example = "2")
			int canceladas,

			@Schema(description = "Lo que falta contra lo planificado. Nunca negativo: se puede "
					+ "realizar de mas, y eso no es un error sino un tratamiento que se extendio",
					example = "12")
			int restantes,

			@Schema(description = "Se alcanzo o se supero la cantidad planificada. Es un aviso, no "
					+ "una transicion", example = "false")
			boolean completo) {

		static AvanceDeItemResponse from(AvanceDeItemView view) {
			return new AvanceDeItemResponse(
					view.ofertaId(),
					view.ofertaNombre(),
					view.planificadas(),
					view.autorizadas(),
					view.realizadas(),
					view.canceladas(),
					view.restantes(),
					view.completo());
		}
	}
}
