package com.akine.clinical.api.dto;

import com.akine.clinical.application.PlanTratamientoView;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un Plan de Tratamiento con su version vigente, tal como sale por la API.
 *
 * <p>Trae la vigente y no el historico completo: el historico se lee con
 * {@code GET /planes-tratamiento/&#123;id&#125;/versiones}, que es otra operacion y deja su propio
 * evento de auditoria.
 *
 * <p><b>No trae avance.</b> El avance se deriva contando sesiones cerradas del Caso y es su propia
 * consulta: meterlo aca haria que abrir la ficha consultara sesiones en cada lectura, y que la
 * ficha y el avance pudieran discrepar sin que se note cual de los dos vale.
 */
@Schema(name = "PlanTratamiento",
		description = "Plan de Tratamiento de un Caso Clinico, con su version vigente")
public record PlanTratamientoResponse(

		@Schema(example = "77")
		long id,

		@Schema(description = "Caso del que cuelga. Un Caso admite UN solo plan activo",
				example = "17")
		long casoClinicoId,

		@Schema(description = "Correlativo DENTRO del caso: \"el segundo plan de este problema\". "
				+ "No es el id, no es el numero de caso y no es el de sesion", example = "2")
		int numeroPlan,

		@Schema(description = "BORRADOR, ACTIVO, SUSPENDIDO o FINALIZADO. No hay baja logica: un "
				+ "plan no se borra, se finaliza",
				allowableValues = {"BORRADOR", "ACTIVO", "SUSPENDIDO", "FINALIZADO"},
				example = "ACTIVO")
		String estado,

		@Schema(example = "2026-09-19T11:02:44Z")
		Instant creadoEn,

		@Schema(description = "Cuenta que creo el plan", example = "8")
		long creadoPor,

		@Schema(description = "Instante de la PRIMERA activacion. No se limpia al suspender ni al "
				+ "finalizar: un plan que estuvo vigente lo estuvo")
		Instant activadoEn,

		@Schema(description = "Cuenta que lo activo")
		Long activadoPor,

		@Schema(description = "Instante de la suspension vigente. LA REANUDACION LO LIMPIA: la "
				+ "suspension anterior, con su motivo, queda en el historial del plan")
		Instant suspendidoEn,

		@Schema(description = "Por que se discontinuo. Presente solo mientras el plan este "
				+ "SUSPENDIDO")
		String motivoSuspension,

		@Schema(description = "Instante de la finalizacion. FINALIZADO es terminal: no se reabre")
		Instant finalizadoEn,

		@Schema(description = "Cuenta que lo finalizo. Cuando la finalizacion la produjo la "
				+ "activacion de OTRO plan del caso, es quien activo ese otro")
		Long finalizadoPor,

		@Schema(description = "Por que termino")
		String motivoFinalizacion,

		@Schema(description = "Version vigente del contenido, con sus practicas. Ausente solo si "
				+ "el plan quedo sin contenido, que no es un estado que el backend produzca")
		PlanVersionResponse versionVigente,

		@Schema(description = "Devolvela al modificar o al transicionar. NO es numeroVersion, que "
				+ "es el orden del contenido: son dos versiones distintas y se llaman parecido",
				example = "3")
		long version) {

	public static PlanTratamientoResponse from(PlanTratamientoView view) {
		return new PlanTratamientoResponse(
				view.id(),
				view.casoClinicoId(),
				view.numeroPlan(),
				view.estado(),
				view.creadoEn(),
				view.creadoPor(),
				view.activadoEn(),
				view.activadoPor(),
				view.suspendidoEn(),
				view.motivoSuspension(),
				view.finalizadoEn(),
				view.finalizadoPor(),
				view.motivoFinalizacion(),
				view.versionVigente() == null
						? null
						: PlanVersionResponse.from(view.versionVigente()),
				view.version());
	}
}
