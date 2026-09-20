package com.akine.clinical.domain;

/**
 * Los cuatro estados de un Plan de Tratamiento (M11, RF-M11-006).
 *
 * <pre>
 *   BORRADOR -&gt; ACTIVO -&gt; SUSPENDIDO -&gt; ACTIVO -&gt; FINALIZADO
 * </pre>
 *
 * <p><b>Completar la cantidad estimada NO lleva a {@link #FINALIZADO}</b>, y no es un olvido:
 * RN-M11-004 lo prohibe explicitamente. El sistema avisa —el avance dice que el item esta
 * completo— y la decision es clinica. Un cierre automatico por contador es confundir planificado
 * con realizado en la direccion contraria, y es la tentacion que mas se parece a una mejora de
 * producto (challenge seccion 4.3).
 *
 * <p><b>No hay {@code active} al lado de esto.</b> Un plan no se borra: se finaliza. Un
 * {@code active} conviviendo con el estado daria dos formas de que un plan "no este", que es como
 * se construye una consulta que se olvida de una. Misma decision que {@link EstadoCaso}.
 */
public enum EstadoPlan {

	/**
	 * Se esta armando. Se edita libremente <b>sin versionar</b>.
	 *
	 * <p>Un plan que nunca se activo no tiene historia que preservar, y versionar cada tecleo
	 * llenaria {@code plan_tratamiento_version} de ruido que nadie va a leer nunca.
	 */
	BORRADOR,

	/**
	 * Vigente. Toda modificacion relevante <b>versiona</b> (RF-M11-004).
	 *
	 * <p>Hay <b>uno solo por Caso</b>, y lo hace cumplir {@code uk_plan_activo_por_caso}: el Caso
	 * ES el problema terapeutico, asi que dos planes activos para el mismo problema significan que
	 * en realidad son dos problemas. Activar un plan nuevo finaliza el anterior en la misma
	 * transaccion.
	 */
	ACTIVO,

	/**
	 * El tratamiento se discontinuo pero no se cerro.
	 *
	 * <p>Merece estado propio y no se resuelve finalizando y creando otro: eso perderia la
	 * distincion entre "termino" y "se freno", que es justamente lo que alguien quiere saber al
	 * releer la historia seis meses despues.
	 *
	 * <p><b>Un plan suspendido sigue ocupando el lugar del activo del Caso</b>, porque
	 * {@code activo_key} solo libera al FINALIZADO: suspender no es abrir la puerta a un plan
	 * nuevo, es frenar el que hay.
	 */
	SUSPENDIDO,

	/**
	 * Terminal. No se reabre: se crea un plan nuevo.
	 *
	 * <p>Se sigue leyendo entero, con todas sus versiones y su historial. Eso es lo que distingue
	 * "termino" de "no existio" (regla maestra 10).
	 */
	FINALIZADO;

	/** {@code true} si el plan todavia admite cambios de contenido. */
	public boolean admiteCambios() {
		return this != FINALIZADO;
	}

	/** {@code true} si una modificacion de contenido tiene que crear una version nueva. */
	public boolean exigeVersionar() {
		return this == ACTIVO || this == SUSPENDIDO;
	}
}
