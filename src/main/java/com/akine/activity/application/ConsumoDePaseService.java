package com.akine.activity.application;

import com.akine.activity.domain.AsistenciaActividad;
import com.akine.activity.domain.AsistenciaEvento;
import com.akine.activity.domain.ClaseProgramada;
import com.akine.activity.domain.MovimientoPase;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ConsumoDeClase;
import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.NetoDeAsistencia;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseElegible;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import com.akine.offering.spi.EsquemaDeCobro;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * suelta en una oferta {@code POR_PACK} (U-39 (b), decidida el 17/10/2026: informar, como hasta
 * ahora); el devengo por clase (RF-M18-008) es del esquema {@code POR_CLASE} y lo hace
 * {@link DevengoPorClaseService}.
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
	private final AuditTrail auditTrail;

	public ConsumoDePaseService(
			PaseServicioRepositoryPort pases,
			MovimientoPaseRepositoryPort movimientos,
			OfertaDirectory ofertas,
			AuditTrail auditTrail) {

		this.pases = pases;
		this.movimientos = movimientos;
		this.ofertas = ofertas;
		this.auditTrail = auditTrail;
	}

	/**
	 * Lleva el credito tomado por la asistencia a lo que su resultado actual pide.
	 *
	 * @param recienCreada true si la asistencia se inserto en esta misma transaccion: su id es
	 *                 nuevo, ningun movimiento puede tenerlo todavia y el neto es cero sin preguntar
	 * @param evento   el evento de asistencia (registro o correccion) que se acaba de escribir: su id
	 *                 es el {@code origen_id} del movimiento
	 * @param motivo   el de la correccion, si la hubo; viaja en la devolucion
	 * @param lecturas la zona de la sede y el esquema de cobro, leidos una vez por pedido
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public ConsumoDeCredito aplicar(
			ClaseProgramada clase, AsistenciaActividad asistencia, boolean recienCreada,
			AsistenciaEvento evento, long actorCuentaId, String motivo, Instant ahora,
			LecturasDeConsumo lecturas) {

		long organizationId = asistencia.getOrganizationId();
		long eventoId = evento.getId();
		List<NetoDeAsistencia> tomado = recienCreada
				? List.of()
				: movimientos.netoDeAsistencia(organizationId, asistencia.getId());
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
		// AKINE-08.08 (RF-M29-008): una oferta POR_ABONO se cubre igual que una POR_PACK, con el
		// abono vigente el dia de la clase. "Cubierta sin obligacion unitaria" es exactamente esto:
		// el credito del periodo se toma y ninguna deuda nace (RN-M29-007).
		boolean consumeCredito = lecturas.esquema(clase.getOfertaId(), () -> ofertas
						.esquemaDeCobro(organizationId, clase.getConsultorioId(), clase.getOfertaId()))
				.filter(esquema -> esquema == EsquemaDeCobro.POR_PACK || esquema == EsquemaDeCobro.POR_ABONO)
				.isPresent();
		if (!consumeCredito) {
			return ConsumoDeCredito.noAplica();
		}
		return consumir(clase, asistencia, eventoId, actorCuentaId, ahora, lecturas.zona());
	}

	/**
	 * Devuelve los creditos que tomaron las asistencias de una clase que se acaba de cancelar
	 * (RF-M29-004, RF-M28-006 paso 7, RN-M29-009).
	 *
	 * <p>Lo llama {@code ClaseService.cancelar} <b>solo cuando hubo transicion</b> y con la clase
	 * bloqueada en exclusivo, en {@code READ_COMMITTED}: el registro de asistencia toma la clase en
	 * compartido antes de tocar un pase, asi que ningun consumo de esta clase puede estar a mitad de
	 * camino y todos los commiteados se ven.
	 *
	 * <p><b>Nunca devuelve dos veces</b> (CA-M29-004-06), por tres capas: la cancelacion es
	 * idempotente y solo la primera llega aca; solo se devuelve lo que tiene neto distinto de cero;
	 * y {@code uk_movimiento_origen} sobre {@code (pase, CANCELACION, clase)}. La devolucion lleva la
	 * asistencia, asi su neto vuelve a cero. <b>No borra el consumo</b>: lo compensa.
	 *
	 * <p>Un pase {@code ANULADO} no recibe nada: no se puede anular uno con consumos (ver
	 * {@code AjusteDePaseService}), asi que el caso no ocurre, y si ocurriera no se inventa un
	 * movimiento que el saldo no refleja.
	 *
	 * @return cuantos creditos se devolvieron
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public int devolverPorCancelacion(
			ClaseProgramada clase, long actorCuentaId, String motivo, Instant ahora) {

		long organizationId = clase.getOrganizationId();
		int devueltos = 0;
		for (ConsumoDeClase consumo : movimientos.consumosDeLaClase(organizationId, clase.getId())) {
			if (consumo.neto() >= 0 || pases.devolverUno(organizationId, consumo.paseId()) != 1) {
				continue;
			}
			int saldo = pases.saldoActual(organizationId, consumo.paseId());
			movimientos.registrar(MovimientoPase.devolucionPorCancelacion(
					organizationId, consumo.paseId(), saldo, clase.getId(), consumo.asistenciaId(),
					actorCuentaId, motivo, ahora));
			Map<String, String> detalles = new LinkedHashMap<>();
			detalles.put("claseId", String.valueOf(clase.getId()));
			detalles.put("asistenciaId", String.valueOf(consumo.asistenciaId()));
			detalles.put("saldo", String.valueOf(saldo));
			auditTrail.record(new AuditEntry(
					organizationId, clase.getConsultorioId(), actorCuentaId,
					"PASE_CREDITO_DEVUELTO_POR_CANCELACION", ENTIDAD, consumo.paseId(),
					null, null, detalles, motivo, null, ahora));
			devueltos++;
		}
		if (devueltos > 0) {
			log.info("Creditos devueltos por cancelacion de clase: claseId={} devueltos={}",
					clase.getId(), devueltos);
		}
		return devueltos;
	}

	private ConsumoDeCredito consumir(
			ClaseProgramada clase, AsistenciaActividad asistencia, long eventoId, long actorCuentaId,
			Instant ahora, ZoneId zonaDeLaSede) {

		long organizationId = asistencia.getOrganizationId();
		// La vigencia se evalua el DIA DE LA CLASE en la zona de la sede, no el dia en que alguien
		// marca la lista: tomar asistencia el lunes de una clase del viernes no puede dejar afuera
		// un pase que vencia el sabado.
		LocalDate dia = LocalDate.ofInstant(clase.getInicio(), zonaDeLaSede);

		for (PaseElegible elegible : pases.lockElegibles(
				organizationId, asistencia.getPersonaId(), clase.getOfertaId(), dia)) {
			long paseId = elegible.paseId();
			if (pases.consumirUno(organizationId, paseId, dia) == 1) {
				// El saldo se leyo bajo el FOR UPDATE que esta transaccion todavia retiene, y el
				// UPDATE que acaba de tocar la fila resto exactamente uno: no hace falta releerlo.
				int saldo = elegible.creditosDisponibles() - 1;
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
