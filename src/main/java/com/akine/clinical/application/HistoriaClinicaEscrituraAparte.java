package com.akine.clinical.application;

import com.akine.clinical.domain.HistoriaClinica;
import com.akine.clinical.domain.port.ClinicalRepositoryPorts.HistoriaClinicaRepositoryPort;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * El INSERT de la historia clinica, en su propia transaccion.
 *
 * <p>Mismo patron y misma razon que {@link AdjuntoClinicoEscrituraAparte},
 * {@code ComprobanteIniciador}, {@code AgendaSedeIniciador} y {@code ConvenioLockIniciador}: es la
 * <b>regla 2 del Paquete B</b>, que este repositorio ya pago cinco veces.
 * <b>Atrapar una excepcion de persistencia no des-marca la transaccion.</b> Cuando el flush choca
 * contra {@code uk_historia_clinica_persona_vigente}, Hibernate marca la transaccion
 * {@code rollbackOnly} antes de que la excepcion salga; el {@code catch} corre igual, pero sobre
 * una sesion inutilizable, y Spring termina en {@code UnexpectedRollbackException} —o en
 * {@code AssertionFailure}— al commitear. {@code organization.application.OnboardingService} lo
 * documenta con esas mismas palabras porque alla se vio primero.
 *
 * <p>Lo que eso significaba aca: <b>dos aperturas simultaneas de la misma historia devolvian
 * 500</b> en vez de la historia que gano la carrera. Es la ruta critica del modulo clinico —toda
 * sesion, entrada y adjunto pasa por {@code asegurar}— y es idempotente por contrato.
 *
 * <h2>Consecuencia asumida, y por que esta acotada</h2>
 *
 * <p>La fila queda commiteada aunque la transaccion de negocio termine en rollback. En este caso
 * el precio es bajo y se puede nombrar con precision: una historia clinica recien abierta es un
 * <b>contenedor vacio</b> —no tiene antecedentes, ni entradas, ni adjuntos—, asi que una fila
 * huerfana no afirma ningun hecho clinico falso. El unique impide que se duplique, la apertura es
 * idempotente por definicion, y el reintento del cliente encuentra esa misma fila.
 *
 * <p>Lo unico que se puede perder es el evento {@code HISTORIA_CLINICA_OPENED}, que se escribe en
 * la transaccion de negocio: si esta revierte despues del INSERT, la historia existe y el evento
 * no. <b>No queda sin rastro</b>: {@code abierta_en} y {@code abierta_por} son columnas de la
 * propia fila, escritas por este INSERT, asi que quien la abrio y cuando siguen siendo
 * respondibles. Y todo acceso posterior —lectura incluida— si queda auditado. La alternativa
 * —dejar el INSERT adentro— no evita nada: cambia ese hueco por un 500 en cada apertura
 * concurrente.
 */
@Component
public class HistoriaClinicaEscrituraAparte {

	private final HistoriaClinicaRepositoryPort historias;

	public HistoriaClinicaEscrituraAparte(HistoriaClinicaRepositoryPort historias) {
		this.historias = historias;
	}

	/**
	 * Inserta la historia en su propia transaccion y la commitea.
	 *
	 * <p>Con {@code saveAndFlush} para que el unique decida <b>aca</b> y no al cierre de la
	 * transaccion de negocio, que es donde ya no hay forma de recuperarse.
	 *
	 * @throws org.springframework.dao.DataIntegrityViolationException si otra apertura gano la
	 *                                                                 carrera. El llamador la
	 *                                                                 resuelve releyendo la
	 *                                                                 historia vigente, con su
	 *                                                                 propia transaccion intacta
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public HistoriaClinica insertar(HistoriaClinica historia) {
		return historias.saveAndFlush(historia);
	}

	/**
	 * Relee la historia vigente en una transaccion nueva, con snapshot propio.
	 *
	 * <p>Es la relectura de la ganadora tras perder la carrera del unique. No puede correr en la
	 * transaccion de negocio: esa ya hizo el {@code SELECT} previo (vacio) y, con
	 * {@code REPEATABLE READ}, seguiria viendo ese snapshot —no encontraria la fila que la otra
	 * apertura ya commiteo— y la apertura concurrente terminaria en 409 en vez de devolver la
	 * historia que gano.
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public Optional<HistoriaClinica> releerVigente(long organizationId, long personaId) {
		return historias.buscarVigentePorPersona(organizationId, personaId);
	}
}
