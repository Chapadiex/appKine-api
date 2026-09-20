package com.akine.clinical.api.dto;

import com.akine.clinical.domain.PlanItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Una practica que el profesional quiere planificar (RF-M11-002).
 *
 * <h2>Tres campos, y el cuarto que no existe</h2>
 *
 * <p><b>No hay {@code cantidadRealizada} ni {@code cantidadCancelada}, y no es una validacion que
 * alguien pueda sacar</b>: no existe la columna donde guardarlas (RN-M11-001). El backend no acepta
 * un contador de realizadas desde el frontend porque no tiene donde ponerlo, que es la unica forma
 * de cumplir esa regla sin depender de la disciplina de quien escriba el proximo endpoint.
 *
 * <p>Lo realizado se consulta en {@code GET /planes-tratamiento/&#123;id&#125;/avance}, que lo
 * <b>deriva</b> contando sesiones cerradas del Caso.
 *
 * <p>Los topes se comparan contra las constantes del dominio y no contra numeros sueltos: son las
 * mismas que documentan el CHECK de la columna, asi que el dia que una migracion las cambie hay un
 * solo lugar que tocar. Un tope mas alto que la columna no rechaza nada: solo cambia donde explota.
 */
@Schema(name = "PlanItem",
		description = "Practica planificada dentro de un Plan de Tratamiento, con sus cantidades")
public record PlanItemRequest(

		@Schema(description = "Oferta planificada. Tiene que existir en la sede del contexto y "
				+ "estar habilitada hoy (02.07). Que se de de baja DESPUES no invalida el plan",
				example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El item exige declarar la oferta planificada")
		Long ofertaId,

		@Schema(description = "Sesiones que la decision clinica estima. Es el dato de la etapa, y "
				+ "NO es lo realizado: eso se deriva al leer el avance",
				example = "20", requiredMode = Schema.RequiredMode.REQUIRED)
		@NotNull(message = "El item exige una cantidad planificada")
		@Min(value = 1, message = "La cantidad planificada es al menos 1")
		@Max(value = PlanItem.CANTIDAD_MAXIMA,
				message = "La cantidad planificada no puede superar las 9999 sesiones")
		Integer cantidadPlanificada,

		@Schema(description = "Sesiones que la cobertura otorgo, DECLARADAS a mano. Ausente = sin "
				+ "tope declarado, que es distinto de cero. La integracion real con las "
				+ "autorizaciones de M17 es 04.05: hoy nadie verifica este numero contra el "
				+ "financiador, y el sistema lo registra como lo que es, una declaracion",
				example = "10")
		@Min(value = 0, message = "La cantidad autorizada no puede ser negativa")
		@Max(value = PlanItem.CANTIDAD_MAXIMA,
				message = "La cantidad autorizada no puede superar las 9999 sesiones")
		Integer cantidadAutorizada) {
}
