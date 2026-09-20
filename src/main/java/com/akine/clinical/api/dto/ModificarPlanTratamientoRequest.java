package com.akine.clinical.api.dto;

import com.akine.clinical.domain.PlanTratamiento;
import com.akine.clinical.domain.PlanTratamientoVersion;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Cambio de contenido de un Plan de Tratamiento (RF-M11-004).
 *
 * <h2>El mismo cuerpo hace dos cosas distintas, y lo decide el estado</h2>
 *
 * <p>Sobre un plan en <b>BORRADOR</b> reescribe la version 1 en el lugar; sobre uno <b>ACTIVO o
 * SUSPENDIDO</b> escribe una version nueva con su propio juego de items, dejando intactos los de la
 * anterior (RN-M11-003). <b>No hay un campo para elegir</b>, y es deliberado: un {@code versionar}
 * en el cuerpo dejaria al cliente decidir si preservar historia clinica, que es justamente la
 * decision que la regla no delega.
 *
 * <p>Por eso {@link #motivo} es obligatorio <b>solo</b> cuando el plan ya se activo, y esa
 * condicion no se puede expresar con anotaciones: la exige la propia version al construirse, y el
 * rechazo llega como 400.
 *
 * <p><b>Reemplaza el contenido completo.</b> No es un parche por campo ausente, por lo mismo que la
 * edicion de un caso: un PATCH que distingue "no lo mandes" de "vacialo" obliga al cliente a
 * codificar esa diferencia, y la primera pantalla que se olvide borra las indicaciones sin querer.
 * Los items que no vengan <b>no siguen existiendo</b> en la version nueva.
 */
@Schema(description = "Cambio de contenido de un plan. Versiona o no segun el estado del plan")
public record ModificarPlanTratamientoRequest(

		@Schema(description = "Objetivos terapeuticos de esta version",
				example = "Recuperar flexion completa y retomar trote suave",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotBlank(message = "El plan exige declarar sus objetivos terapeuticos")
		@Size(max = PlanTratamientoVersion.TEXTO_MAXIMO,
				message = "Los objetivos no pueden superar los 2000 caracteres")
		String objetivos,

		@Schema(description = "Indicaciones generales. Opcional")
		@Size(max = PlanTratamientoVersion.TEXTO_MAXIMO,
				message = "Las indicaciones no pueden superar los 2000 caracteres")
		String indicaciones,

		@Schema(description = "Sesiones por semana propuestas. No agenda nada", example = "2")
		@Min(value = 1, message = "La frecuencia semanal es al menos 1")
		@Max(value = PlanTratamientoVersion.FRECUENCIA_MAXIMA,
				message = "La frecuencia semanal no puede superar las 21 sesiones")
		Integer frecuenciaSemanal,

		@Schema(description = "Duracion estimada, en semanas", example = "12")
		@Min(value = 1, message = "La duracion es al menos una semana")
		@Max(value = PlanTratamientoVersion.DURACION_MAXIMA,
				message = "La duracion no puede superar las 520 semanas")
		Integer duracionSemanas,

		@Schema(description = "Practicas de ESTA version, completas. Las que no vengan no siguen "
				+ "existiendo aca; las de la version anterior quedan intactas")
		@Valid
		List<PlanItemRequest> items,

		@Schema(description = "Por que se modifica. OBLIGATORIO si el plan ya se activo —sin "
				+ "motivo, una modificacion es indistinguible de una correccion de tipeo—, y se "
				+ "ignora sobre un borrador, que todavia no tiene nada que explicar. Su ausencia "
				+ "sobre un plan vigente se rechaza con 400",
				example = "El paciente tolero mal la carga: se baja la frecuencia a 2 por semana")
		@Size(max = PlanTratamiento.MOTIVO_MAXIMO,
				message = "El motivo no puede superar los 500 caracteres")
		String motivo,

		@Schema(description = "Version que el autor leyo, para el bloqueo optimista. NO es "
				+ "numeroVersion, que es el orden del contenido: son dos versiones distintas y se "
				+ "llaman parecido", example = "3",
				requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "La version esperada es obligatoria")
		Long expectedVersion) {
}
