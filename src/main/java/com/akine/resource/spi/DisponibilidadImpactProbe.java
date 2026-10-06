package com.akine.resource.spi;

import java.time.Instant;

/**
 * Turnos futuros afectados por un cambio de disponibilidad (RF-M05-005, RN-M05-004).
 *
 * <h2>Quien la responde</h2>
 *
 * <p>Desde el paquete E-1, {@code scheduling.infrastructure.DisponibilidadImpactoSobreTurnos}:
 * cuenta los turnos pendientes de la membership en la sede que empiezan en la ventana, como
 * <b>cota superior</b> —no distingue el bloque que se recorta de otro bloque vigente del mismo
 * profesional—. Es el mismo patron que {@code EspacioOccupancyProbe} en 02.02.
 *
 * <h2>Por que es un bean singular y no una lista, a diferencia de {@code EspacioOccupancyProbe}</h2>
 *
 * <p>{@code EspacioOccupancyProbe} admite varios modulos compitiendo por el mismo lugar fisico
 * (turnos e inscripciones), asi que tiene sentido sumar picos de varias fuentes. Los turnos de
 * una membership en una ventana son una sola fuente de verdad —la agenda de {@code scheduling}—
 * y no hay una segunda sonda con la que combinar el resultado. El consumidor
 * ({@code DisponibilidadService}, AKINE-02.04 tarea 7) inyecta un {@code DisponibilidadImpactProbe}
 * unico, no una lista. Hasta E-1, {@code resource} registraba una implementacion nula
 * ({@code Impacto.ninguno()} siempre); la de {@code scheduling} la reemplazo y la nula se borro.
 */
public interface DisponibilidadImpactProbe {

	/**
	 * Turnos futuros que un cambio de disponibilidad dejaria en conflicto.
	 *
	 * @param turnosAfectados cuantos turnos futuros caen fuera de la disponibilidad resultante.
	 *                        Cero cuando no hay ninguno
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
	 * <p>{@code DisponibilidadService} decide que ventana corresponde evaluar: esta firma solo
	 * declara el contrato de la pregunta, no como se calcula la respuesta.
	 */
	Impacto turnosEn(long organizationId, long consultorioId, long membershipId,
			Instant desde, Instant hasta);
}
