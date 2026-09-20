package com.akine.clinical.application;

/**
 * Una practica que el profesional quiere planificar, tal como entra a la aplicacion.
 *
 * <p><b>Tres campos, y los tres son decisiones o declaraciones de quien planifica.</b> No hay un
 * cuarto: la cantidad realizada y la cancelada no entran por aca porque no entran por ningun lado
 * (RN-M11-001). El backend no acepta un contador de realizadas desde el frontend, y no por una
 * validacion que alguien podria sacar: es que no existe la columna donde guardarlo.
 *
 * @param ofertaId             la oferta planificada. Tiene que existir en la sede del contexto y
 *                             estar vigente al planificar (02.07); que se de de baja despues no
 *                             invalida el plan
 * @param cantidadPlanificada  sesiones que la decision clinica estima. Es el dato de la etapa
 * @param cantidadAutorizada   sesiones que la cobertura otorgo, <b>declaradas a mano</b>.
 *                             {@code null} = sin tope declarado, que es distinto de cero. La
 *                             costura real con las autorizaciones de M17 es 04.05: conectarla aca
 *                             seria adelantar media integracion
 */
public record PlanItemPlanificado(
		long ofertaId, int cantidadPlanificada, Integer cantidadAutorizada) {
}
