package com.akine.resource.spi;

import java.time.Instant;

/**
 * Turnos futuros afectados por un cambio de disponibilidad (RF-M05-005, RN-M05-004).
 *
 * <h2>Que responde hoy, y que NO — dicho antes de que alguien lo asuma</h2>
 *
 * <p><b>Hoy devuelve siempre cero, y no es un bug.</b> Los turnos son del modulo
 * {@code scheduling} (F5, M12), que no existe. La forma de sumar esa mitad esta declarada
 * aca y no cambia el contrato: cuando haya una implementacion, {@code turnosAfectados} deja
 * de ser cero y la pantalla de edicion empieza a mostrar el conflicto.
 *
 * <p>Es exactamente el mismo patron que {@code EspacioOccupancyProbe} en 02.02, y por el
 * mismo motivo: una pantalla que interprete "cero conflictos" como "se puede cambiar sin
 * consecuencias" va a dejar turnos huerfanos en cuanto exista la agenda, y el bug no va a
 * parecer de esta etapa.
 *
 * <h2>Por que es un bean singular y no una lista, a diferencia de {@code EspacioOccupancyProbe}</h2>
 *
 * <p>{@code EspacioOccupancyProbe} admite varios modulos compitiendo por el mismo lugar fisico
 * (turnos e inscripciones), asi que tiene sentido sumar picos de varias fuentes. Los turnos de
 * una membership en una ventana son una sola fuente de verdad —la agenda de {@code scheduling}—
 * y no hay una segunda sonda con la que combinar el resultado. Por eso {@code resource}
 * registra HOY una implementacion por defecto de este mismo tipo ({@code Impacto.ninguno()}
 * siempre) en vez de dejar la lista vacia: el consumidor ({@code DisponibilidadService},
 * AKINE-02.04 tarea 7) inyecta un {@code DisponibilidadImpactProbe} unico, no una lista, y el
 * contexto tiene que levantar con algo que responder aunque {@code scheduling} no exista
 * todavia. Cuando F5 traiga la implementacion real, reemplaza a la de hoy; no conviven porque
 * no hay nada que sumar entre las dos.
 */
public interface DisponibilidadImpactProbe {

	/**
	 * Turnos futuros que un cambio de disponibilidad dejaria en conflicto.
	 *
	 * @param turnosAfectados cuantos turnos futuros caen fuera de la disponibilidad resultante.
	 *                        Cero cuando no hay ninguno, o —hoy siempre— porque no existe
	 *                        todavia ningun turno que pueda estarlo
	 * @param primero         instante del primero de esos turnos, para que la pantalla pueda
	 *                        decir "desde el martes". {@code null} cuando {@code turnosAfectados}
	 *                        es cero
	 */
	record Impacto(long turnosAfectados, Instant primero) {

		/** La respuesta de un modulo que no tiene ningun turno que reportar. */
		public static Impacto ninguno() {
			return new Impacto(0L, null);
		}

		/** {@code true} si hay al menos un turno futuro en conflicto. */
		public boolean hayAlgo() {
			return turnosAfectados > 0;
		}
	}

	/**
	 * Turnos de esa membership, en esa sede, que caerian en {@code [desde, hasta)} si la
	 * disponibilidad cambiara de la forma que se esta evaluando.
	 *
	 * <p>{@code scheduling} (F5) es quien sabe que ventana corresponde evaluar: esta firma solo
	 * declara el contrato de la pregunta, no como se calcula la respuesta.
	 */
	Impacto turnosEn(long organizationId, long consultorioId, long membershipId,
			Instant desde, Instant hasta);
}
