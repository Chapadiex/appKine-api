package com.akine.resource.spi;

import java.time.Instant;

/**
 * Un modulo que compromete lugares de un espacio declara aca cuantos tiene tomados.
 *
 * <h2>Por que un puerto invertido y no una consulta directa</h2>
 *
 * <p>La ocupacion real de un box son sus turnos y sus inscripciones, y esas tablas son de
 * {@code scheduling} (M12) y de {@code activity} (M28). Que
 * {@code resource} las consultara violaria la regla 1 de AGENT.md seccion 4 —cada tabla tiene
 * un modulo propietario— y ademas dibujaria la flecha {@code resource -> scheduling}, o sea
 * del cimiento hacia el consumidor: ciclo garantizado, y ArchUnit lo rechazaria.
 *
 * <p>Invertido, la flecha va {@code scheduling -> resource.spi}: permitida, unidireccional y ya
 * probada — es el mismo patron de {@code organization.spi.ConsultorioDeactivationProbe}.
 *
 * <h2>Que decide, exactamente</h2>
 *
 * <p>Dos operaciones de esta etapa preguntan, y preguntan cosas distintas:
 * <ul>
 *   <li><b>Reducir la capacidad</b> (RF-M04-002, caso borde "capacidad reducida bajo
 *       ocupacion"): necesita el PICO de ocupacion simultanea de aca en adelante. Bajar la
 *       capacidad de 10 a 4 con una clase de 8 personas ya inscripta deja ocho personas
 *       citadas a un lugar donde no entran.</li>
 *   <li><b>Dar de baja el espacio</b> (RF-M04-006, caso borde "baja con reservas futuras"):
 *       necesita saber si queda algo comprometido, sin importar cuanto.</li>
 * </ul>
 *
 * <p>En F2 la lista de implementaciones era vacia y los codigos
 * {@code espacio-capacity-below-occupancy} y {@code espacio-has-active-references} quedaron
 * reservados en el contrato. Desde el paquete E-1 los emite la implementacion de turnos,
 * {@code scheduling.infrastructure.EspacioOcupadoPorTurnos}. {@code activity} todavia no
 * declara la suya.
 */
public interface EspacioOccupancyProbe {

	/**
	 * Ocupacion que un modulo declara sobre un espacio.
	 *
	 * @param type  vocabulario del modulo que responde, p.ej. {@code turnos-futuros} o
	 *              {@code inscripciones-vigentes}. Viaja al cliente dentro del Problem Details,
	 *              asi que es un valor estable y <b>sin datos de personas</b>
	 * @param peak  maxima cantidad de lugares comprometidos simultaneamente en la ventana
	 *              consultada. Es un PICO y no un total: dos turnos consecutivos de una persona
	 *              cada uno ocupan un lugar, no dos, y sumarlos rechazaria reducciones
	 *              perfectamente validas
	 */
	record Occupancy(String type, long peak) {

		/** La respuesta de un modulo que no tiene nada comprometido. */
		public static Occupancy ninguna() {
			return new Occupancy(null, 0L);
		}

		public boolean hayAlguna() {
			return peak > 0;
		}
	}

	/**
	 * Pico de ocupacion simultanea del espacio desde {@code at} hacia adelante.
	 *
	 * <p>Se invoca DENTRO de la transaccion que ya bloqueo la fila del espacio con
	 * {@code FOR UPDATE}, con lo que la respuesta no puede quedar vieja entre la consulta y el
	 * commit. Una implementacion que abra su propia transaccion rompe esa garantia y
	 * reintroduce la carrera: debe unirse a la existente.
	 *
	 * <p>Solo mira hacia adelante. Reducir la capacidad de un box no invalida nada de lo que ya
	 * ocurrio ahi (RN-M04-003): un historico con mas gente de la que hoy entra es un dato, no
	 * una inconsistencia.
	 */
	Occupancy peakOccupancyFrom(long organizationId, long espacioId, Instant at);
}
