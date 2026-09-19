package com.akine.person.application;

import com.akine.person.domain.AdjuntoAdministrativo;
import com.akine.person.domain.port.PersonRepositoryPorts.AdjuntoRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las dos escrituras sobre {@code adjunto_administrativo} que NO pueden vivir en la transaccion de
 * negocio, cada una en la suya.
 *
 * <p>Es la gemela de {@code clinical.application.AdjuntoClinicoEscrituraAparte} y vive en
 * {@code person} a proposito: {@code adjunto_administrativo} es tabla de este modulo y la regla 1
 * de {@code AGENT.md} §4 no admite que la escriba nadie mas. Compartir una clase entre los dos
 * modulos seria exactamente la capa global que la regla 3 prohibe.
 *
 * <p>La razon es la <b>regla 2 del Paquete B</b>, que este repositorio ya pago cinco veces y que
 * {@code organization.application.OnboardingService} documenta con nombre y apellido:
 * <b>atrapar una excepcion de persistencia no des-marca la transaccion.</b> Cuando el flush choca
 * contra {@code uk_adjunto_contenido_vigente}, Hibernate marca la transaccion
 * {@code rollbackOnly} antes de que la excepcion salga; el {@code catch} corre igual, pero sobre
 * una sesion inutilizable, y Spring termina en {@code UnexpectedRollbackException} —o en
 * {@code AssertionFailure}— al commitear. O sea un <b>500 en la subida concurrente del mismo
 * documento</b>, justo donde el {@code @Operation} del controller promete idempotencia en
 * mayusculas.
 *
 * <h2>Consecuencia asumida, y como queda acotada</h2>
 *
 * <p>La fila queda commiteada aunque la transaccion de negocio termine en rollback. Es el precio
 * de poder recuperarse del choque, y esta acotado igual que del lado clinico: si lo que falla es
 * el blob, {@link #marcarNoDisponible} deja la fila diciendo la verdad —un documento sin
 * contenido, visible como tal— en vez de mentir con {@code DISPONIBLE}; y si lo que falla es algo
 * posterior, el reintento del cliente encuentra esa misma fila por checksum y se resuelve como
 * idempotente. Dejar el INSERT adentro no evita el problema: lo cambia por el 500.
 */
@Component
public class AdjuntoEscrituraAparte {

	private static final Logger log = LoggerFactory.getLogger(AdjuntoEscrituraAparte.class);

	private final AdjuntoRepositoryPort adjuntos;

	public AdjuntoEscrituraAparte(AdjuntoRepositoryPort adjuntos) {
		this.adjuntos = adjuntos;
	}

	/**
	 * Inserta la fila en su propia transaccion y la commitea.
	 *
	 * <p>Con {@code saveAndFlush} para que el unique decida <b>aca</b>, antes de que el servicio
	 * toque el disco, y no al cierre de una transaccion que ya escribio el binario.
	 *
	 * @throws org.springframework.dao.DataIntegrityViolationException si otra subida gano la
	 *                                                                 carrera. El llamador la
	 *                                                                 resuelve buscando por
	 *                                                                 checksum, con su propia
	 *                                                                 transaccion intacta
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public AdjuntoAdministrativo insertar(AdjuntoAdministrativo adjunto) {
		return adjuntos.saveAndFlush(adjunto);
	}

	/**
	 * Marca el adjunto {@code NO_DISPONIBLE} en su propia transaccion.
	 *
	 * <p>Recarga la fila en vez de recibir la entidad: la que tiene el llamador esta manejada por
	 * OTRA sesion, y mutarla ahi dejaria la marca dentro de la transaccion que esta por revertirse
	 * —que es el defecto que esta clase existe para evitar—.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void marcarNoDisponible(long organizationId, long personaId, long adjuntoId) {
		adjuntos.buscarDeLaPersona(organizationId, personaId, adjuntoId)
				.ifPresent(adjunto -> {
					adjunto.marcarNoDisponible();
					adjuntos.save(adjunto);
					log.warn("Adjunto administrativo marcado NO_DISPONIBLE: adjuntoId={} "
							+ "personaId={}", adjuntoId, personaId);
				});
	}
}
