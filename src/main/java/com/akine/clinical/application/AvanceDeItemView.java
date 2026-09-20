package com.akine.clinical.application;

/**
 * El avance de una practica planificada. <b>Todos los conteos de aca son derivados</b> (RF-M11-005).
 *
 * <p>{@link #realizadas} y {@link #canceladas} no salen de ninguna columna: salen de contar sesiones
 * cerradas del Caso por oferta, a traves de {@code clinical.spi.RealizadoEnElCasoProbe}. Esa es toda
 * la etapa en una frase — planificado no es realizado, y lo realizado lo sabe {@code encounter}.
 *
 * @param realizadas sesiones cerradas con el paciente presente para esa oferta, dentro del Caso
 * @param canceladas sesiones cerradas con el paciente ausente. Se muestran aparte porque el
 *                   profesional necesita ver por que el avance no avanza
 * @param restantes  lo que falta contra lo planificado, nunca negativo. Se puede realizar de mas:
 *                   eso no es un error, es un tratamiento que se extendio, y {@link #completo}
 *                   sigue siendo cierto
 * @param completo   se alcanzo o se supero la cantidad planificada. <b>Es un aviso, no una
 *                   transicion</b> (RN-M11-004): no finaliza el plan ni cierra el caso, decide el
 *                   profesional. Un cierre automatico por contador es confundir planificado con
 *                   realizado en la direccion contraria
 */
public record AvanceDeItemView(
		long ofertaId,
		String ofertaNombre,
		int planificadas,
		Integer autorizadas,
		int realizadas,
		int canceladas,
		int restantes,
		boolean completo) {

	/** Arma el avance de un item cruzando lo planificado con lo que la sonda conto. */
	public static AvanceDeItemView de(PlanItemView item, int realizadas, int canceladas) {
		int planificadas = item.cantidadPlanificada();
		return new AvanceDeItemView(
				item.ofertaId(),
				item.ofertaNombre(),
				planificadas,
				item.cantidadAutorizada(),
				realizadas,
				canceladas,
				Math.max(0, planificadas - realizadas),
				realizadas >= planificadas);
	}
}
