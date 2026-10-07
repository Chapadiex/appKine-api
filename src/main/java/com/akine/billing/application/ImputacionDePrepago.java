package com.akine.billing.application;

import com.akine.billing.domain.Cobro;
import com.akine.billing.domain.CobroImputacion;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.port.CobroRepositoryPort;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Imputa el prepago de un turno a la deuda que devengo el cierre de su sesion (AKINE E-6,
 * DP-06 / ADR-0013: "cuando la prestacion se concreta y nace la obligacion, el anticipo se imputa
 * a una obligacion compatible").
 *
 * <h2>Despues del commit del cierre, en su propia transaccion</h2>
 *
 * <p>La deuda se devenga DENTRO del cierre (07.01) y eso no cambia. La imputacion no: corre
 * despues del commit, con {@code REQUIRES_NEW}, y si falla se loguea y el anticipo queda como
 * saldo a favor para imputarlo a mano (F-3). <b>Cerrar no cobra</b> (DP-06): que una carrera con
 * una anulacion o un dato raro del cobro hiciera fallar el cierre clinico seria atar la historia
 * clinica a la caja, que es exactamente lo que ADR-0013 prohibe.
 *
 * <h2>Que se imputa</h2>
 *
 * <ul>
 *   <li>Solo a la deuda del <b>paciente</b> de esa sesion —{@code PARTICULAR} o {@code COSEGURO}—,
 *       nunca a la del financiador: esa se reclama en una presentacion (F-4).</li>
 *   <li>{@code min(saldo a favor, saldo de la deuda)}. Si el anticipo sobra, lo que sobra queda a
 *       favor; si no alcanza, la deuda queda {@code PARCIAL}.</li>
 *   <li>Misma persona y misma moneda; si no, no se imputa (se loguea).</li>
 * </ul>
 *
 * <p><b>No mueve caja</b>: la plata entro cuando se cobro el prepago, como en toda imputacion
 * posterior de F-3.
 *
 * <h2>Concurrencia e idempotencia</h2>
 *
 * <p>Toma el lock del cobro como las operaciones de F-3 ({@code SELECT ... FOR UPDATE} en
 * {@code READ_COMMITTED}): una anulacion concurrente se serializa y, si gano, el cobro aparece
 * anulado y no se imputa. Un segundo disparo encuentra la imputacion ya hecha y no hace nada;
 * {@code uk_cobro_imputacion (cobro_id, obligacion_id)} es la red.
 */
@Service
public class ImputacionDePrepago {

	private static final Logger log = LoggerFactory.getLogger(ImputacionDePrepago.class);

	private final CobroRepositoryPort cobros;
	private final ObligacionRepositoryPort obligaciones;
	private final AuditTrail auditTrail;

	public ImputacionDePrepago(
			CobroRepositoryPort cobros, ObligacionRepositoryPort obligaciones, AuditTrail auditTrail) {

		this.cobros = cobros;
		this.obligaciones = obligaciones;
		this.auditTrail = auditTrail;
	}

	/**
	 * El cierre de una sesion que salio de un turno.
	 *
	 * @param cerradaPorCuentaId quien cerro la atencion; queda como autor de la imputacion
	 */
	public record CierreConTurno(
			long organizationId, long consultorioId, long sesionId, long turnoId, long personaId,
			Long cerradaPorCuentaId) {
	}

	/**
	 * Imputa el prepago del turno a la deuda del paciente de la sesion.
	 *
	 * @return lo imputado, o vacio si no habia nada que imputar
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
	public Optional<BigDecimal> imputarAlCierre(CierreConTurno cierre) {
		Optional<Long> prepagoId = cobros.prepagoVigenteDelTurno(cierre.organizationId(), cierre.turnoId());
		if (prepagoId.isEmpty()) {
			return Optional.empty();
		}
		// El lock es la primera carga del cobro: ver la cabecera de CobroPosteriorService.
		Optional<Cobro> bloqueado = cobros.findByIdInScopeParaEscribir(
				cierre.organizationId(), cierre.consultorioId(), prepagoId.get());
		if (bloqueado.isEmpty() || bloqueado.get().estaAnulado()
				|| bloqueado.get().getSaldoAFavor().signum() <= 0) {
			return Optional.empty();
		}
		Cobro cobro = bloqueado.get();

		Optional<Obligacion> deuda = obligaciones.findDeLaSesion(cierre.sesionId()).stream()
				.filter(o -> o.getResponsable() == Responsable.PACIENTE)
				.filter(Obligacion::admiteCobro)
				.findFirst();
		if (deuda.isEmpty()) {
			return Optional.empty();
		}
		Obligacion obligacion = deuda.get();
		if (!obligacion.getPersonaId().equals(cobro.getPersonaId())
				|| !obligacion.getMoneda().equals(cobro.getMoneda())) {
			log.warn("Prepago no imputado al cierre: la deuda es de otra persona o en otra moneda. "
							+ "cobroId={} obligacionId={} sesionId={}",
					cobro.getId(), obligacion.getId(), cierre.sesionId());
			return Optional.empty();
		}
		boolean yaImputado = cobro.getImputaciones().stream()
				.anyMatch(previa -> previa.getObligacionId().equals(obligacion.getId()));
		if (yaImputado) {
			return Optional.empty();
		}

		BigDecimal importe = cobro.getSaldoAFavor().min(obligacion.getSaldo());
		if (importe.signum() <= 0) {
			return Optional.empty();
		}

		Instant ahora = Instant.now();
		long autor = cierre.cerradaPorCuentaId() != null
				? cierre.cerradaPorCuentaId()
				: cobro.getCobradoPorCuentaId();
		cobro.imputar(CobroImputacion.posterior(
				cierre.organizationId(), obligacion.getId(), importe, ahora, autor, null, null));
		if (cobros.descontarSaldo(cierre.organizationId(), obligacion.getId(), importe) == 0) {
			// La deuda cambio entre la lectura y el UPDATE (otro cobro se la llevo): la transaccion
			// cae entera y el anticipo queda intacto, a favor.
			throw new IllegalStateException("La obligacion " + obligacion.getId()
					+ " ya no admite que se le imputen " + importe);
		}
		cobros.actualizarEstadoPorSaldo(cierre.organizationId(), obligacion.getId());
		cobros.flush();

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("origen", "PREPAGO_AL_CIERRE");
		detalles.put("turnoId", String.valueOf(cierre.turnoId()));
		detalles.put("sesionId", String.valueOf(cierre.sesionId()));
		detalles.put("obligacionId", String.valueOf(obligacion.getId()));
		detalles.put("importe", importe.toPlainString());
		detalles.put("saldoAFavorRestante", cobro.getSaldoAFavor().toPlainString());
		auditTrail.record(new AuditEntry(
				cierre.organizationId(), cobro.getConsultorioId(), autor,
				AuditEvents.COBRO_SALDO_IMPUTADO, AuditEvents.ENTITY_COBRO, cobro.getId(),
				null, null, detalles, null, AuditEvents.correlationId(), ahora));

		log.info("Prepago imputado al cierre: cobroId={} obligacionId={} sesionId={} importe={} "
						+ "restante={}",
				cobro.getId(), obligacion.getId(), cierre.sesionId(), importe, cobro.getSaldoAFavor());
		return Optional.of(importe);
	}
}
