package com.akine.contracting.application;

import com.akine.contracting.domain.port.ConvenioRepositoryPorts.ConvenioLockRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea la fila de {@code convenio_lock} en su PROPIA transaccion, antes de que la escritura
 * empiece.
 *
 * <h2>El defecto que esto evita, y que este proyecto ya pago tres veces</h2>
 *
 * <p>Crear la fila perezosamente <b>dentro</b> de la transaccion que la va a bloquear —leer, ver
 * que no esta, insertarla— parece correcto y no lo es. Cuando N escrituras concurrentes son las
 * primeras de una sede, las N leen "no existe" y las N intentan el mismo INSERT. InnoDB toma locks
 * sobre el hueco del indice unico y el resultado <b>no</b> es una violacion de unique que una
 * pierda limpiamente: es un <b>deadlock</b>, y el perdedor recibe
 * {@code CannotAcquireLockException}, que no es ninguno de los 409 que la pantalla sabe manejar.
 *
 * <p>Y el {@code try/catch} no salva: atrapar una excepcion de persistencia no des-marca la
 * transaccion, asi que Spring intenta commitear una transaccion marcada para rollback y el llamador
 * recibe {@code UnexpectedRollbackException}. La excepcion hay que EVITARLA, no atraparla — de ahi
 * el {@code INSERT ... ON DUPLICATE KEY UPDATE} del repositorio.
 *
 * <p>Ya se pago con {@code agenda_sede} (05.02, lo encontro {@code TurnoConcurrenteIT} contra MySQL
 * real), con {@code consultorio_calendario} (02.04, corregido en {@code 4e3d666}) y con
 * {@code sesion_numerador} (06.05). Esta clase existe para que M16 no sea la cuarta.
 *
 * <h2>Por que una transaccion aparte</h2>
 *
 * <p>{@code REQUIRES_NEW} hace que el INSERT se commitee de inmediato y libere sus locks, en vez de
 * retenerlos hasta el final de la escritura. La ventana en la que dos pueden chocar pasa de "toda
 * la transaccion" a "un INSERT", y lo que se recibe en esa ventana ya es un error de escritura
 * normal y no un deadlock entre transacciones largas.
 */
@Component
public class ConvenioLockIniciador {

	private static final Logger log = LoggerFactory.getLogger(ConvenioLockIniciador.class);

	private final ConvenioLockRepositoryPort locks;

	public ConvenioLockIniciador(ConvenioLockRepositoryPort locks) {
		this.locks = locks;
	}

	/**
	 * Se asegura de que la fila exista. Idempotente y seguro entre transacciones concurrentes.
	 *
	 * <p>No devuelve nada a proposito: la fila que importa es la que el llamador va a BLOQUEAR
	 * dentro de su propia transaccion, y devolver la instancia de esta otra invitaria a usarla sin
	 * el lock.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void asegurar(long organizationId, long consultorioId) {
		locks.crearSiFalta(organizationId, consultorioId);
		log.debug("Fila-lock de convenios de la sede {} asegurada", consultorioId);
	}
}
