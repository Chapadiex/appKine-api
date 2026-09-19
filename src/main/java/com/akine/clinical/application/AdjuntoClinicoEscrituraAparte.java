package com.akine.clinical.application;

import com.akine.clinical.domain.AdjuntoClinico;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.AdjuntoClinicoRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las dos escrituras sobre {@code adjunto_clinico} que NO pueden vivir en la transaccion de
 * negocio, cada una en la suya.
 *
 * <p>Mismo patron y misma razon que {@code ComprobanteIniciador}, {@code AgendaSedeIniciador} y
 * {@code ConvenioLockIniciador}: es la <b>regla 2 del Paquete B</b>, que este repositorio ya pago
 * cuatro veces. <b>Atrapar una excepcion de persistencia no des-marca la transaccion.</b> Cuando
 * el flush choca contra un unique, Hibernate marca la transaccion {@code rollbackOnly} antes de
 * que la excepcion salga; el {@code catch} corre igual, pero sobre una sesion inutilizable, y
 * Spring lanza {@code UnexpectedRollbackException} al commitear. La unica forma de recuperarse es
 * que la escritura que puede chocar corra en una transaccion <b>propia</b>, que muera sola.
 *
 * <h2>{@link #insertar} — el alta que puede chocar contra el unique</h2>
 *
 * <p>Si dos subidas simultaneas del mismo contenido llegan a insertar, la perdedora choca contra
 * {@code uk_adjunto_clinico_contenido_vigente}. Con el INSERT aca adentro, lo que muere es esta
 * transaccion y no la del servicio, que sigue viva para resolver el choque como la respuesta
 * idempotente que el contrato promete. Un pre-{@code SELECT} no sustituye a esto: no cierra la
 * carrera, solo la hace menos probable —y el pre-chequeo del servicio existe por otra razon, que
 * es ahorrar escribir el binario en el camino feliz—.
 *
 * <p><b>Consecuencia asumida:</b> la fila queda commiteada aunque la transaccion de negocio
 * termine en rollback. Es el precio de poder recuperarse del choque, y esta acotado: si lo que
 * falla es el blob, {@link #marcarNoDisponible} deja la fila diciendo la verdad —un adjunto sin
 * contenido, visible como tal en el listado— en vez de mentir con {@code DISPONIBLE}; y si lo que
 * falla es algo posterior, el reintento del cliente encuentra esa misma fila por checksum y se
 * resuelve como idempotente. La alternativa —dejar el INSERT adentro— no evita el problema: lo
 * cambia por un 500 en cada subida concurrente.
 *
 * <h2>{@link #marcarNoDisponible} — la marca que tiene que sobrevivir al rollback</h2>
 *
 * <p>Cuando el almacenamiento perdio el binario, la descarga responde 409 lanzando
 * {@code AdjuntoClinicoNoDisponibleException}. Esa excepcion revierte la transaccion de la
 * descarga y se llevaria puesta la marca si la marca estuviera adentro: la columna se quedaria en
 * {@code DISPONIBLE} para siempre y el listado seguiria afirmando que el estudio esta, contra lo
 * que prometen el {@code @Operation} del controller y la cabecera de {@code V46}. Es la misma
 * leccion que {@link ClinicalSupportAccessAuditor}: un hecho que hay que poder revisar despues no
 * puede depender de que la operacion de negocio termine bien.
 *
 * <p>La fila no esta bloqueada por la transaccion que llama: la descarga solo la leyo, y un
 * {@code SELECT} sin {@code FOR UPDATE} no toma locks en InnoDB. Por eso esta transaccion puede
 * escribirla sin esperar a la otra.
 */
@Component
public class AdjuntoClinicoEscrituraAparte {

	private static final Logger log =
			LoggerFactory.getLogger(AdjuntoClinicoEscrituraAparte.class);

	private final AdjuntoClinicoRepositoryPort adjuntos;

	public AdjuntoClinicoEscrituraAparte(AdjuntoClinicoRepositoryPort adjuntos) {
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
	public AdjuntoClinico insertar(AdjuntoClinico adjunto) {
		return adjuntos.saveAndFlush(adjunto);
	}

	/**
	 * Marca el adjunto {@code NO_DISPONIBLE} en su propia transaccion.
	 *
	 * <p>Recarga la fila en vez de recibir la entidad: la que tiene el llamador esta manejada por
	 * OTRA sesion, y mutarla ahi dejaria la marca dentro de la transaccion que esta por revertirse
	 * —que es exactamente el defecto que esta clase existe para arreglar—.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void marcarNoDisponible(long organizationId, long historiaClinicaId, long adjuntoId) {
		adjuntos.buscarDeLaHistoria(organizationId, historiaClinicaId, adjuntoId)
				.ifPresent(adjunto -> {
					adjunto.marcarNoDisponible();
					adjuntos.save(adjunto);
					log.warn("Adjunto clinico marcado NO_DISPONIBLE: adjuntoId={} "
							+ "historiaClinicaId={}", adjuntoId, historiaClinicaId);
				});
	}
}
