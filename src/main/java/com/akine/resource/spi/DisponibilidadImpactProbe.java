package com.akine.resource.spi;

import java.time.Instant;
import java.util.List;

/**
 * Los turnos pendientes sobre los que {@code resource} evalua un cambio de disponibilidad
 * (RF-M05-005, RN-M05-004).
 *
 * <h2>Quien la responde</h2>
 *
 * <p>Desde el paquete E-1, {@code scheduling.infrastructure.DisponibilidadImpactoSobreTurnos},
 * sobre la tabla {@code turno}. Es el mismo patron que {@code EspacioOccupancyProbe} en 02.02:
 * {@code resource} declara la pregunta y {@code scheduling}, que ya depende de {@code resource.spi},
 * la contesta. La arista va en el mismo sentido que antes y no se agrega ninguna.
 *
 * <h2>Por que devuelve los turnos y no una cuenta (A-11)</h2>
 *
 * <p>Hasta A-11 la sonda contaba los turnos de la ventana y {@code resource} informaba esa cuenta
 * como <b>cota superior</b>: con solo la ventana no se puede distinguir un turno del bloque que se
 * recorta de uno de OTRO bloque vigente del mismo profesional (limite (a) de la decision
 * pendiente 8). La correccion no es pasarle el bloque a {@code scheduling} —eso le ensenaria las
 * reglas de disponibilidad a un modulo que no las tiene, y duplicaria el calculo que
 * {@code DisponibilidadEfectivaCalculator} ya hace—, sino al reves: {@code scheduling} devuelve los
 * turnos pendientes, con su intervalo, y {@code resource} decide cuales quedan afuera comparando
 * la disponibilidad efectiva antes y despues del cambio. La sonda sigue sin saber nada de bloques.
 *
 * <p>Sin datos del paciente: id, profesional e intervalo. Es lo que la agenda ya expone a quien
 * gestiona la sede, y alcanza para que la pantalla liste "estos turnos quedan afuera".
 *
 * <h2>Por que es un bean singular y no una lista</h2>
 *
 * <p>Los turnos de una sede en una ventana son una sola fuente de verdad —la agenda de
 * {@code scheduling}— y no hay una segunda sonda con la que combinar el resultado.
 */
public interface DisponibilidadImpactProbe {

	/**
	 * Un turno pendiente —reservado o confirmado, no dado de baja—.
	 *
	 * @param turnoId      id del turno, para que la pantalla lo abra en la agenda
	 * @param membershipId profesional del turno
	 * @param inicio       instante de inicio
	 * @param fin          instante de fin, exclusivo
	 */
	record TurnoPendiente(long turnoId, long membershipId, Instant inicio, Instant fin) {
	}

	/**
	 * Los turnos pendientes de esa sede que <b>empiezan</b> en {@code [desde, hasta)}, ordenados por
	 * inicio.
	 *
	 * @param membershipId el profesional, o {@code null} para los de todos los profesionales de la
	 *                     sede —lo que pide una excepcion de alcance sede—
	 */
	List<TurnoPendiente> pendientesEn(
			long organizationId, long consultorioId, Long membershipId, Instant desde, Instant hasta);
}
