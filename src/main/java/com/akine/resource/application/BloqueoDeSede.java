package com.akine.resource.application;

import com.akine.resource.domain.CalendarioSede;
import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;

/**
 * El unico punto de serializacion de los writes de disponibilidad de una sede.
 *
 * <h2>Por que el lock esta aca y no sobre las filas que se van a tocar</h2>
 *
 * <p>MySQL 8.4 no tiene exclusion constraints —son de PostgreSQL—, asi que el solapamiento de
 * bloques se valida en aplicacion. Un {@code SELECT ... FOR UPDATE} sobre los bloques existentes
 * NO alcanza: bloquear filas que existen no impide que otra transaccion INSERTE una fila nueva
 * en el hueco, y dos altas concurrentes terminan pisandose sin que ninguna vea a la otra.
 *
 * <p>Por eso el lock se toma sobre la fila de {@code consultorio_calendario} de la sede, que es
 * unica por sede y se crea a demanda. Serializa los writes de disponibilidad por sede, que es
 * aceptable: editar horarios es una accion administrativa de baja frecuencia, no un camino
 * caliente.
 *
 * <p><b>El lock se toma al PRINCIPIO de la transaccion, antes de leer nada.</b> Leer primero en
 * modo compartido y bloquear despues es una escalada S-&gt;X: con dos transacciones en el mismo
 * camino no es una espera, es un deadlock. Por el mismo motivo, aca se INTENTA el lock antes de
 * comprobar si la fila existe, en vez de preguntar con una lectura comun.
 *
 * <h2>Por que existe este archivo</h2>
 *
 * <p>Esta rutina vivia en TRES copias casi identicas —{@code DisponibilidadService},
 * {@code ExcepcionService} y {@code CalendarioService}—, las tres correctas. Es exactamente la
 * forma que produce "arreglamos el lock en dos de los tres lugares" un anio despues, cuando el
 * protocolo cambie. Mismo argumento con el que se extrajo {@link ZonaSede}.
 *
 * <h2>La ventana angosta que queda, declarada</h2>
 *
 * <p>Si dos requests son los PRIMEROS de esa sede al mismo tiempo, los dos ven la fila ausente y
 * los dos la insertan; el unique de {@code consultorio_calendario} rechaza a uno y ese request
 * falla. No se atrapa la violacion porque no serviria de nada — la sesion JPA queda inutilizable
 * despues de un flush fallido y el {@code lockByScope} siguiente tiraria {@code AssertionFailure}
 * igual—. El remedio es un reintento del cliente, que ya encuentra la fila creada. Es la unica
 * vez en la vida de una sede que puede pasar, y en la practica el {@code PUT} de politica de
 * calendario crea la fila mucho antes.
 */
final class BloqueoDeSede {

	private BloqueoDeSede() {
		// Utilidad de concurrencia.
	}

	/**
	 * Toma el {@code FOR UPDATE} sobre la fila de politica de la sede, creandola a demanda si es
	 * la primera vez, y devuelve la fila bloqueada.
	 *
	 * <p>Los llamadores que solo necesitan el efecto de serializacion —el alta y la edicion de
	 * bloques, el alta y la baja de excepciones— pueden descartar el resultado;
	 * {@code CalendarioService} lo usa porque ademas la va a editar.
	 *
	 * @throws IllegalStateException si la fila no se puede bloquear ni siquiera despues de
	 *         crearla. Nunca deberia pasar; seguir sin el lock si podria, y eso seria escribir
	 *         disponibilidad sin el unico mecanismo que impide que dos altas se pisen
	 */
	static CalendarioSede tomar(
			CalendarioSedeRepositoryPort calendarios, long organizationId, long consultorioId) {

		return calendarios.lockByScope(organizationId, consultorioId)
				.orElseGet(() -> {
					calendarios.save(new CalendarioSede(organizationId, consultorioId));
					return calendarios.lockByScope(organizationId, consultorioId)
							.orElseThrow(() -> new IllegalStateException(
									"El calendario de la sede " + consultorioId + " no se pudo "
											+ "bloquear inmediatamente despues de crearlo"));
				});
	}
}
