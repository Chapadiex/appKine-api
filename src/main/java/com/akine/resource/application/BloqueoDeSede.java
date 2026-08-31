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
 * unica por sede. Serializa los writes de disponibilidad por sede, que es aceptable: editar
 * horarios es una accion administrativa de baja frecuencia, no un camino caliente.
 *
 * <p><b>El lock se toma al PRINCIPIO de la transaccion, antes de leer nada.</b> Leer primero en
 * modo compartido y bloquear despues es una escalada S-&gt;X: con dos transacciones en el mismo
 * camino no es una espera, es un deadlock.
 *
 * <h2>La fila tiene que existir ANTES, y por que</h2>
 *
 * <p>La crea {@link CalendarioSedeIniciador} en una transaccion aparte, y no aca. Hasta que se
 * corrigio, esta clase la creaba perezosamente —bloquear, ver que no esta, insertarla, volver a
 * bloquear— y su javadoc afirmaba que "el unique de {@code consultorio_calendario} rechaza a
 * uno" de los dos requests que compiten, y trataba el desenlace como una ventana angosta que el
 * cliente arregla reintentando.
 *
 * <p><b>Eso era falso.</b> Cuando N escrituras concurrentes son las PRIMERAS de una sede, las N
 * ven la fila ausente y las N intentan el mismo INSERT; el perdedor recibe un 500 —violacion de
 * unique o deadlock, segun como InnoDB resuelva los gap locks— en un pedido legitimo, y no uno
 * de los 409 que la pantalla de disponibilidad sabe manejar. El detalle completo, y por que un
 * try/catch tampoco alcanzaba, esta en el javadoc de {@link CalendarioSedeIniciador}.
 *
 * <h2>Por que existe este archivo</h2>
 *
 * <p>Esta rutina vivia en TRES copias casi identicas —{@code DisponibilidadService},
 * {@code ExcepcionService} y {@code CalendarioService}—, las tres correctas. Es exactamente la
 * forma que produce "arreglamos el lock en dos de los tres lugares" un anio despues, cuando el
 * protocolo cambie. Mismo argumento con el que se extrajo {@link ZonaSede}.
 */
final class BloqueoDeSede {

	private BloqueoDeSede() {
		// Utilidad de concurrencia.
	}

	/**
	 * Toma el {@code FOR UPDATE} sobre la fila de politica de la sede y la devuelve.
	 *
	 * <p>Los llamadores que solo necesitan el efecto de serializacion —el alta y la edicion de
	 * bloques, el alta y la baja de excepciones— pueden descartar el resultado;
	 * {@code CalendarioService} lo usa porque ademas la va a editar.
	 *
	 * @throws IllegalStateException si la fila no existe. Nunca deberia pasar: quien llama tiene
	 *         que haber invocado {@link CalendarioSedeIniciador#asegurar} antes, y en su propia
	 *         transaccion. Seguir sin el lock si podria, y eso seria escribir disponibilidad sin
	 *         el unico mecanismo que impide que dos altas se pisen
	 */
	static CalendarioSede tomar(
			CalendarioSedeRepositoryPort calendarios, long organizationId, long consultorioId) {

		return calendarios.lockByScope(organizationId, consultorioId)
				.orElseThrow(() -> new IllegalStateException(
						"El calendario de la sede " + consultorioId + " no existe al tomar el "
								+ "lock. CalendarioSedeIniciador#asegurar tiene que correr antes, "
								+ "y en su propia transaccion."));
	}
}
