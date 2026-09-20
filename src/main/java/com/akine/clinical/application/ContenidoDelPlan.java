package com.akine.clinical.application;

import java.util.List;

/**
 * Lo que un Plan de Tratamiento dice, sin los datos que lo identifican.
 *
 * <p>Es el cuerpo de una <b>version</b>: objetivos, indicaciones, la regla de recurrencia sugerida
 * y las practicas con sus cantidades. La misma forma sirve para crear el plan y para modificarlo,
 * porque son la misma decision clinica escrita dos veces — lo que cambia es si produce una version
 * nueva o se escribe sobre la version 1, y eso lo decide el <b>estado del plan</b>, no el cuerpo
 * del pedido (RF-M11-004).
 *
 * <p><b>No tiene cantidades realizadas ni canceladas, y no puede tenerlas.</b> No existe la columna
 * donde guardarlas y no existe el camino por donde entrarian (RN-M11-001): el avance se deriva
 * contando sesiones cerradas del Caso.
 *
 * @param frecuenciaSemanal sesiones por semana <b>propuestas</b>. Es una sugerencia de recurrencia
 *                          y <b>no agenda nada</b>: agendar es de {@code scheduling}, y este modulo
 *                          no lo importa ni por el {@code spi} (regla maestra 2)
 * @param duracionSemanas   duracion estimada. Estimada: el plan no vence solo, y completar la
 *                          cantidad estimada tampoco lo finaliza (RN-M11-004)
 * @param items             practicas planificadas. Puede venir vacia mientras el plan sea un
 *                          borrador: el profesional suele escribir primero los objetivos
 */
public record ContenidoDelPlan(
		String objetivos,
		String indicaciones,
		Integer frecuenciaSemanal,
		Integer duracionSemanas,
		List<PlanItemPlanificado> items) {

	public ContenidoDelPlan {
		items = items == null ? List.of() : List.copyOf(items);
	}
}
