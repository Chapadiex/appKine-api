package com.akine.person.application;

import com.akine.person.domain.port.PersonRepositoryPorts.CoberturaPersonaLockRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Crea la fila de {@code cobertura_persona_lock} en su PROPIA transaccion, antes de escribir.
 *
 * <p>Crear la fila perezosamente <b>dentro</b> de la transaccion que la bloquea parece correcto y
 * no lo es: cuando N escrituras concurrentes son las primeras de una persona, las N leen "no
 * existe" y las N intentan el mismo INSERT. InnoDB toma locks sobre el hueco del indice unico y el
 * resultado no es una violacion de unique que una pierda limpiamente, sino un <b>deadlock</b>. Y
 * el {@code try/catch} no salva: atrapar una excepcion de persistencia no des-marca la
 * transaccion, asi que Spring lanza {@code UnexpectedRollbackException} al commitear.
 *
 * <p>Ya se pago tres veces en este repositorio —{@code agenda_sede} (05.02),
 * {@code consultorio_calendario} (02.04) y {@code sesion_numerador} (06.05)—. Esta clase es la
 * cuarta aplicacion del mismo patron, y esta escrita asi desde el principio en vez de descubrirlo
 * de nuevo.
 *
 * <p>{@code REQUIRES_NEW} hace que el INSERT se commitee de inmediato y libere sus locks, en vez
 * de retenerlos hasta el final de la escritura. No devuelve nada a proposito: la fila que importa
 * es la que el llamador va a BLOQUEAR dentro de su propia transaccion, y devolver esta invitaria a
 * usarla sin el lock.
 */
@Component
public class CoberturaLockIniciador {

	private static final Logger log = LoggerFactory.getLogger(CoberturaLockIniciador.class);

	private final CoberturaPersonaLockRepositoryPort candados;

	public CoberturaLockIniciador(CoberturaPersonaLockRepositoryPort candados) {
		this.candados = candados;
	}

	/** Se asegura de que la fila exista. Idempotente y seguro entre transacciones concurrentes. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void asegurar(long organizationId, long personaId) {
		candados.crearSiFalta(organizationId, personaId);
		log.debug("Candado de coberturas de la persona {} asegurado", personaId);
	}
}
