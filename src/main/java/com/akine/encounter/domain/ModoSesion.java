package com.akine.encounter.domain;

/**
 * Como se carga la atencion.
 *
 * <p><b>Ninguno de los dos exige mas campos que el otro</b>, y eso es una regla de negocio y no una
 * comodidad: "seguimiento no exige examen completo". El modo es una decision de la PANTALLA sobre
 * cuanto mostrar, no una validacion del servidor. Si el modo condicionara campos obligatorios, un
 * profesional que empieza en rapida y necesita anotar una cosa mas tendria que cambiar de modo y
 * recargar el formulario.
 */
public enum ModoSesion {

	/**
	 * Seguimiento: dolor, evolucion y poco mas.
	 *
	 * <p>Es el caso mayoritario en un centro de kinesiologia — la mayoria de las sesiones de un
	 * tratamiento son seguimiento, no primera consulta— y por eso la pantalla lo ofrece primero.
	 */
	RAPIDA,

	/** Primera consulta o reevaluacion. El examen y las mediciones son 06.03, fuera del Paquete B. */
	COMPLETA
}
