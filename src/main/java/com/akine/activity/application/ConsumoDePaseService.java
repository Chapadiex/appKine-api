package com.akine.activity.application;

import com.akine.activity.domain.AsistenciaActividad;
import com.akine.activity.domain.AsistenciaEvento;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.MovimientoPase;
import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.NetoDeAsistencia;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import com.akine.offering.spi.EsquemaDeCobro;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * El ledger de creditos movido por la asistencia (AKINE-08.07, RF-M29-003, RN-M29-005).
 *
 * <h2>La regla, entera</h2>
 *
 * <p>La asistencia {@code PRESENTE} o {@code PRESENTE_TARDE} a una clase cuya oferta declara
 * {@code esquema_cobro = POR_PACK} consume <b>un</b> credito del pase elegible de la persona: el de
 * esa oferta, activo, con saldo y vigente el dia de la clase, el que vence antes primero. Una
 * correccion a {@code AUSENTE} lo devuelve con un movimiento {@code DEVOLUCION} —no borra el
 * consumo— y una correccion de vuelta a presente lo vuelve a tomar.
 *
 * <p>El invariante que sostiene todo es que <b>el neto de una asistencia vale 0 o -1</b>: se mira
 * {@code SUM(cantidad)} de sus movimientos (V96, {@code asistencia_id}) y se actua solo si difiere
 * de lo que el resultado pide. Por eso es idempotente sin clave: repetir el mismo resultado no
 * escribe, y dos registros de la misma asistencia no pueden correr a la vez —los serializa el
 * unique de la asistencia en el alta y su {@code @Version} en la correccion—.
 *
 * <h2>Sin pase elegible</h2>
 *
 * <p>La asistencia <b>se registra igual</b>: asistencia y economia son independientes
 * (CA-M19-009-06) y RN-M29-005 solo dice que no se consume. El resultado lo informa
 * ({@code SIN_PASE_ELEGIBLE}) y queda auditado para regularizar. Ningun RF pide devengar una clase
 * suelta en una oferta {@code POR_PACK}; el devengo por clase (RF-M18-008) es del esquema
 * {@code POR_CLASE}, que espera una decision del usuario.
 *
 * <h2>Concurrencia</h2>
 *
 * <p>Los pases candidatos se leen con {@code FOR UPDATE} —lectura actual, no la foto de
 * {@code REPEATABLE READ}— y el descuento es un {@code UPDATE} condicional que sube
 * {@code version}: dos asistencias de la misma persona por el ultimo credito no pueden pasar las
 * dos. Corre en la transaccion del registro de asistencia ({@code MANDATORY}): sin
 * {@code REQUIRES_NEW}, que es la traba del pool que midio G-10.
 *
 * <p><b>Un credito no es plata</b> (RN-M29-006): nada de esto toca la caja ni la obligacion.
 */
@Component
public class ConsumoDePaseService {

	private static final Logger log = LoggerFactory.getLogger(ConsumoDePaseService.class);

	private static final String ENTIDAD = "PASE_SERVICIO";

	private final PaseServicioRepositoryPort pases;
	private final MovimientoPaseRepositoryPort movimientos;
	private final OfertaDirectory ofertas;
	private final ConsultorioDirectory consultorios;
	private final AuditTrail auditTrail;

	public ConsumoDePaseService(
			PaseServicioRepositoryPort pases,
			MovimientoPaseRepositoryPort movimientos,
			OfertaDirectory ofertas,
			ConsultorioDirectory consultorios,
			AuditTrail auditTrail) {

		this.pases = pases;
		this.movimientos = movimientos;
		this.ofertas = ofertas;
		this.consultorios = consultorios;
		this.auditTrail = auditTrail;
	}

	/**
	 * Lleva el credito tomado por la asistencia a lo que su resultado actual pide.
	 *
	 * @param evento   el evento de asistencia (registro o correccion) que se acaba de escribir: su id
	 *                 es el {@code origen_id} del movimiento
	 * @param motivo   el de la correccion, si la hubo; viaja en la devolucion
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public ConsumoDeCredito aplicar(
			ClaseProgramada clase, AsistenciaActividad asistencia, AsistenciaEvento evento,
			long actorCuentaId, String motivo, Instant ahora) {

		long organizationId = asistencia.getOrganizationId();
		long eventoId = evento.getId();
		List<NetoDeAsistencia> tomado = movimientos.netoDeAsistencia(organizationId, asistencia.getId());
		boolean debeConsumir = asistencia.getResultado().estuvo();

		if (!tomado.isEmpty()) {
			if (debeConsumir) {
				return sinCambio(organizationId, tomado.get(0).paseId());
			}
			return devolver(asistencia, tomado.get(0).paseId(), eventoId, actorCuentaId, motivo, ahora);
		}
		if (!debeConsumir) {
			return ConsumoDeCredito.noAplica();
		}
		boolean porPack = ofertas
				.esquemaDeCobro(organizationId, clase.getConsultorioId(), clase.getOfertaId())
				.filter(EsquemaDeCobro.POR_PACK::equals)
				.isPresent();
		if (!porPack) {
			return ConsumoDeCredito.noAplica();
		}
		return consumir(clase, asistencia, eventoId, actorCuentaId, ahora);
	}

	private ConsumoDeCredito consumir(
			ClaseProgramada clase, AsistenciaActividad asistencia, long eventoId, long actorCuentaId,
			Instant ahora) {

		long organizationId = asistencia.getOrganizationId();
		// La vigencia se evalua el DIA DE LA CLASE en la zona de la sede, no el dia en que alguien
		// marca la lista: tomar asistencia el lunes de una clase del viernes no puede dejar afuera
		// un pase que vencia el sabado.
		LocalDate dia = LocalDate.ofInstant(clase.getInicio(),
				ZonaDeSede.de(consultorios, organizationId, clase.getConsultorioId()));

		for (Long paseId : pases.lockElegibles(
				organizationId, asistencia.getPersonaId(), clase.getOfertaId(), dia)) {
			if (pases.consumirUno(organizationId, paseId, dia) == 1) {
				int saldo = pases.saldoActual(organizationId, paseId);
				movimientos.registrar(MovimientoPase.consumoPorAsistencia(
						organizationId, paseId, saldo, eventoId, asistencia.getId(), actorCuentaId,
						ahora));
				auditar(asistencia, paseId, "PASE_CREDITO_CONSUMIDO", saldo, actorCuentaId, null, ahora);
				log.info("Credito consumido: paseId={} asistenciaId={} saldo={}",
						paseId, asistencia.getId(), saldo);
				return new ConsumoDeCredito(ConsumoDeCredito.Efecto.CONSUMIDO, paseId, saldo);
			}
		}

		auditar(asistencia, null, "PASE_SIN_CREDITO_ELEGIBLE", null, actorCuentaId, null, ahora);
		log.info("Asistencia sin pase elegible: asistenciaId={} personaId={} ofertaId={}",
				asistencia.getId(), asistencia.getPersonaId(), clase.getOfertaId());
		return new ConsumoDeCredito(ConsumoDeCredito.Efecto.SIN_PASE_ELEGIBLE, null, null);
	}

	private ConsumoDeCredito devolver(
			AsistenciaActividad asistencia, long paseId, long eventoId, long actorCuentaId,
			String motivo, Instant ahora) {

		long organizationId = asistencia.getOrganizationId();
		if (pases.devolverUno(organizationId, paseId) != 1) {
			// Solo pasa con un pase ANULADO o con un ledger que no cuadra contra el saldo: nada que
			// esta etapa pueda arreglar sola. No se inventa un movimiento que no ocurrio.
			throw new IllegalStateException(
					"No se pudo devolver el credito del pase " + paseId + " a la asistencia "
							+ asistencia.getId());
		}
		int saldo = pases.saldoActual(organizationId, paseId);
		movimientos.registrar(MovimientoPase.devolucionPorCorreccion(
				organizationId, paseId, saldo, eventoId, asistencia.getId(), actorCuentaId, motivo,
				ahora));
		auditar(asistencia, paseId, "PASE_CREDITO_DEVUELTO", saldo, actorCuentaId, motivo, ahora);
		log.info("Credito devuelto por correccion: paseId={} asistenciaId={} saldo={}",
				paseId, asistencia.getId(), saldo);
		return new ConsumoDeCredito(ConsumoDeCredito.Efecto.DEVUELTO, paseId, saldo);
	}

	private ConsumoDeCredito sinCambio(long organizationId, long paseId) {
		return new ConsumoDeCredito(
				ConsumoDeCredito.Efecto.SIN_CAMBIO, paseId, pases.saldoActual(organizationId, paseId));
	}

	private void auditar(
			AsistenciaActividad asistencia, Long paseId, String evento, Integer saldo,
			long actorCuentaId, String motivo, Instant ahora) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("asistenciaId", String.valueOf(asistencia.getId()));
		detalles.put("claseId", String.valueOf(asistencia.getClaseId()));
		detalles.put("personaId", String.valueOf(asistencia.getPersonaId()));
		if (saldo != null) {
			detalles.put("saldo", String.valueOf(saldo));
		}
		auditTrail.record(new AuditEntry(
				asistencia.getOrganizationId(), asistencia.getConsultorioId(), actorCuentaId, evento,
				paseId == null ? "ASISTENCIA_ACTIVIDAD" : ENTIDAD,
				paseId == null ? asistencia.getId() : paseId,
				null, null, detalles, motivo, null, ahora));
	}
}
