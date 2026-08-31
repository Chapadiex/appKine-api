package com.akine.resource.application;

import com.akine.resource.domain.port.DisponibilidadRepositoryPorts.CalendarioSedeRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea la fila de {@code consultorio_calendario} en su PROPIA transaccion, antes de que la
 * escritura de disponibilidad que la va a bloquear empiece.
 *
 * <h2>El defecto que esto arregla</h2>
 *
 * <p>Hasta ahora la fila se creaba perezosamente <b>dentro</b> de la transaccion de la escritura
 * —{@code lockByScope}, ver que no esta, insertarla, volver a bloquear—. Parece correcto y no lo
 * es: cuando N escrituras concurrentes son las PRIMERAS de una sede, las N ven la fila ausente y
 * las N intentan el mismo INSERT, y el perdedor se lleva un <b>500</b> en un pedido
 * perfectamente legitimo.
 *
 * <p>El desenlace exacto lo decide InnoDB y <b>no es determinista</b>: puede ser la violacion de
 * unique ({@code DataIntegrityViolationException}) o, si las transacciones quedan trabadas
 * compitiendo por los gap locks del indice, un <b>deadlock</b> con
 * {@code CannotAcquireLockException}. Las dos se observaron: la primera con dos hilos en
 * {@code DisponibilidadIT}, la segunda con este mismo patron en {@code scheduling}. Ninguna de
 * las dos es uno de los 409 que la pantalla de disponibilidad sabe manejar.
 *
 * <p>El javadoc viejo de {@link BloqueoDeSede} llamaba a esto "una ventana angosta" cuyo
 * "remedio es un reintento del cliente". <b>Eso es lo que era falso</b>: un 500 no es un remedio,
 * es el defecto, y no hay nada que garantice que el desenlace sea siquiera el mismo dos veces.
 *
 * <p>Quedaba latente porque {@code DisponibilidadIT} creaba la fila explicitamente antes de su
 * carrera —"la fila que sirve de punto de serializacion existe antes de la carrera", decia su
 * propio comentario— asi que el camino "primera escritura de la sede" nunca se ejercitaba. Ahora
 * lo cubre {@code las_dos_primeras_altas_de_la_sede_no_pasan_las_dos}, que se verifico que falla
 * contra el codigo anterior.
 *
 * <h2>Por que una transaccion aparte</h2>
 *
 * <p>{@code REQUIRES_NEW} hace que el INSERT commitee de inmediato y libere sus locks, en vez de
 * retenerlos hasta el final de la escritura de disponibilidad. La ventana en la que dos pueden
 * chocar pasa de "toda la transaccion de negocio" a "un INSERT".
 *
 * <p>Y ese INSERT ya no puede fallar: {@code crearSiFalta} es
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}, una sola sentencia atomica sin lectura previa.
 * Envolver el camino viejo en un try/catch <b>no habria alcanzado</b>: atrapar una excepcion de
 * persistencia no des-marca la transaccion, y Spring termina lanzando
 * {@code UnexpectedRollbackException} al commitear. La excepcion hay que evitarla, no atraparla.
 */
@Component
public class CalendarioSedeIniciador {

	private static final Logger log = LoggerFactory.getLogger(CalendarioSedeIniciador.class);

	private final CalendarioSedeRepositoryPort calendarios;

	public CalendarioSedeIniciador(CalendarioSedeRepositoryPort calendarios) {
		this.calendarios = calendarios;
	}

	/**
	 * Se asegura de que la fila exista. Idempotente y seguro entre transacciones concurrentes.
	 *
	 * <p>No devuelve nada a proposito: la fila que importa es la que el llamador va a BLOQUEAR
	 * dentro de su propia transaccion, y devolver la instancia de esta otra invitaria a usarla
	 * sin el lock.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void asegurar(long organizationId, long consultorioId) {
		calendarios.crearSiFalta(organizationId, consultorioId);
		log.debug("Calendario de la sede {} asegurado", consultorioId);
	}
}
