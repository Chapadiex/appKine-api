package com.akine.clinical.api.dto;

import com.akine.clinical.domain.PlanTratamientoVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Alta de un Plan de Tratamiento dentro de un Caso Clinico (RF-M11-001).
 *
 * <p>El plan <b>nace en BORRADOR</b>, aunque venga completo. Activar es una decision explicita y
 * otra operacion, porque tiene un efecto que no se deshace: finaliza el plan que estuviera vigente
 * en ese Caso.
 *
 * <p>La frecuencia y la duracion son una <b>propuesta de recurrencia</b>. Este endpoint no crea
 * turnos, no crea series y no toca la agenda: un plan que agenda es la regla maestra 2 rota.
 */
@Schema(description = "Alta de un Plan de Tratamiento. Nace en BORRADOR (RF-M11-001)")
public record CrearPlanTratamientoRequest(

		@Schema(description = "Objetivos terapeuticos. Es contenido clinico y es obligatorio: un "
				+ "plan sin objetivos no dice a donde se quiere llegar, que es lo unico que "
				+ "despues permite evaluar si el tratamiento sirvio",
				example = "Recuperar flexion completa de rodilla derecha y marcha sin claudicacion",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El plan exige declarar sus objetivos terapeuticos")
		@Size(max = PlanTratamientoVersion.TEXTO_MAXIMO,
				message = "Los objetivos no pueden superar los 2000 caracteres")
		String objetivos,

		@Schema(description = "Indicaciones generales del tratamiento. Opcional",
				example = "Frio local post sesion. Evitar impacto hasta la semana 4")
		@Size(max = PlanTratamientoVersion.TEXTO_MAXIMO,
				message = "Las indicaciones no pueden superar los 2000 caracteres")
		String indicaciones,

		@Schema(description = "Sesiones por semana PROPUESTAS. Es una sugerencia de recurrencia y "
				+ "NO AGENDA NADA: agendar es de la agenda, y este endpoint no la toca",
				example = "3")
		@Min(value = 1, message = "La frecuencia semanal es al menos 1")
		@Max(value = PlanTratamientoVersion.FRECUENCIA_MAXIMA,
				message = "La frecuencia semanal no puede superar las 21 sesiones")
		Integer frecuenciaSemanal,

		@Schema(description = "Duracion estimada, en semanas. Estimada: el plan no vence solo, y "
				+ "completar la cantidad estimada tampoco lo finaliza (RN-M11-004)",
				example = "8")
		@Min(value = 1, message = "La duracion es al menos una semana")
		@Max(value = PlanTratamientoVersion.DURACION_MAXIMA,
				message = "La duracion no puede superar las 520 semanas")
		Integer duracionSemanas,

		@Schema(description = "Practicas planificadas con sus cantidades. PUEDE VENIR VACIA "
				+ "mientras el plan sea un borrador: el profesional suele escribir primero los "
				+ "objetivos y despues decidir las practicas")
		@Valid
		List<PlanItemRequest> items) {
}
