package com.akine.scheduling.application;


import com.akine.scheduling.domain.port.SchedulingRepositoryPorts.AgendaSedeRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea la fila de {@code agenda_sede} en su PROPIA transaccion, antes de que la reserva empiece.
 *
 * <h2>El defecto que esto arregla, y como se encontro</h2>
 *
 * <p>Crear la fila perezosamente <b>dentro</b> de la transaccion de la reserva —leer, ver que no
 * esta, insertarla— parece correcto y no lo es. Cuando N reservas concurrentes son las primeras de
 * una sede, las N leen "no existe" y las N intentan el mismo INSERT. InnoDB toma locks sobre el
 * hueco del indice unico y el resultado <b>no</b> es una violacion de unique que una pierda
 * limpiamente: es un <b>deadlock</b>, y el perdedor recibe {@code CannotAcquireLockException}, que
 * no es ninguno de los 409 de agenda que la pantalla sabe manejar.
 *
 * <p>Lo encontro {@code TurnoConcurrenteIT} la primera vez que corrio contra MySQL real. Un test
 * unitario con mocks no podia verlo: no hay mock que reproduzca el gestor de locks de InnoDB.
 *
 * <p><b>El mismo patron existe en 02.04</b>, en {@code BloqueoDeSede} sobre
 * {@code consultorio_calendario}. Alli el defecto sigue latente: su test de concurrencia crea la
 * fila explicitamente antes de la carrera —"la fila que sirve de punto de serializacion existe
 * antes de la carrera", dice su propio comentario— asi que nunca ejercita este camino. Queda
 * anotado; arreglarlo es una etapa de M05, no de esta.
 *
 * <h2>Por que una transaccion aparte</h2>
 *
 * <p>{@code REQUIRES_NEW} hace que el INSERT se commitee de inmediato y libere sus locks, en vez de
 * retenerlos hasta el final de la reserva. La ventana en la que dos pueden chocar pasa de "toda la
 * transaccion de reserva" a "un INSERT", y lo que se recibe en esa ventana ya es un error de
 * escritura normal y no un deadlock entre transacciones largas.
 *
 * <p>Igual se toleran los dos desenlaces —clave duplicada y deadlock— porque con concurrencia alta
 * el segundo sigue siendo posible. En los dos casos <b>la fila queda creada por el que gano</b>,
 * que es lo unico que importa: quien llama solo necesita que exista para poder bloquearla.
 */
@Component
public class AgendaSedeIniciador {

	private static final Logger log = LoggerFactory.getLogger(AgendaSedeIniciador.class);

	private final AgendaSedeRepositoryPort agendas;

	public AgendaSedeIniciador(AgendaSedeRepositoryPort agendas) {
		this.agendas = agendas;
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
		agendas.crearSiFalta(organizationId, consultorioId);
		log.debug("Agenda de la sede {} asegurada", consultorioId);
	}
}
