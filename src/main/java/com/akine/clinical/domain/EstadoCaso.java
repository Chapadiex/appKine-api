package com.akine.clinical.domain;

/**
 * Los dos estados de un Caso Clinico. Dos, y nada mas.
 *
 * <p>No hay {@code SUSPENDIDO} ni {@code EN_PAUSA}: ningun RF los pide y un estado sin transicion
 * que lo produzca es modelo muerto, que despues aparece en consultas que nadie sabe si tienen que
 * incluirlo. La reapertura de RF-M10-006 devuelve a {@link #ACTIVO} y queda registrada en
 * {@code caso_evento}; pide reabrir, no un estado nuevo.
 *
 * <p><b>Y no hay baja logica al lado del estado.</b> Cerrar no es borrar: un caso abierto por
 * error se cierra con motivo. Un {@code active} conviviendo con esto daria dos formas de que un
 * caso "no este", que es como se construye una consulta que se olvida de una.
 */
public enum EstadoCaso {

	/** El caso admite sesiones, ediciones y cambios de equipo. */
	ACTIVO,

	/**
	 * El caso se cerro con motivo. No admite sesiones nuevas ni edicion de contenido clinico.
	 *
	 * <p>Se sigue leyendo entero, con todo su historial: eso es lo que distingue "termino" de "no
	 * existio" (regla maestra 10).
	 */
	CERRADO
}
