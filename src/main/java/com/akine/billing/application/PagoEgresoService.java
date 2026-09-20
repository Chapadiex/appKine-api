package com.akine.billing.application;

import com.akine.billing.domain.Egreso;
import com.akine.billing.domain.MovimientoCaja;
import com.akine.billing.domain.OrigenMovimiento;
import com.akine.billing.domain.PagoEgreso;
import com.akine.billing.domain.exception.EgresoNoPagableException;
import com.akine.billing.domain.exception.EgresoNotAccessibleException;
import com.akine.billing.domain.exception.EgresoSaldoInsuficienteException;
import com.akine.billing.domain.exception.PagoEgresoNotAccessibleException;
import com.akine.billing.domain.port.EgresoRepositoryPort;
import com.akine.billing.domain.port.MovimientoCajaRepositoryPort;
import com.akine.billing.domain.port.PagoEgresoRepositoryPort;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * El acto de pagar, y de deshacerlo (RF-M22-002, RF-M22-005).
 *
 * <h2>Esta clase es la unica que hace salir plata por un egreso</h2>
 *
 * <p>{@link EgresoService} no mueve un peso: un egreso es <b>lo que se debe</b>. Acá se juntan las
 * tres cosas, cada una con su dueno y su propia condicion:
 *
 * <ol>
 *   <li><b>La deuda no se paga dos veces</b> —
 *       {@code UPDATE egreso ... WHERE estado = 'CONFIRMADO' AND saldo_pendiente >= :importe}.</li>
 *   <li><b>El cajon no queda en rojo</b> —
 *       {@code UPDATE jornada_caja ... WHERE estado = 'ABIERTA' AND saldo_arqueo >= :importe},
 *       reusado tal cual de 07.03.</li>
 *   <li>Y el <b>hecho monetario</b> queda asentado en el mismo ledger que todo lo demas.</li>
 * </ol>
 *
 * <p><b>Cero filas es la respuesta, no un error tecnico</b>, en las dos. Sin lock, sin lectura
 * previa, sin ventana entre leer y escribir y sin deadlock posible. Es lo que 07.02 hizo para
 * imputar y 04.05 para consumir.
 *
 * <h2>El caso que rompe el diseno, y como se rompe si falta una condicion</h2>
 *
 * <p>Fin de mes, 80.000 en el cajon, dos administrativos registran al mismo tiempo dos pagos en
 * efectivo de 50.000. Si los dos entran, la caja queda en −20.000 —un estado que el mundo fisico no
 * admite— y el arqueo de esa noche registraria una diferencia de 20.000 <b>sobre un faltante que
 * nunca existio</b>, que alguien tendria que justificar por escrito. Con la segunda condicion, el
 * segundo afecta cero filas y recibe 409 con el saldo disponible.
 *
 * <h2>Anular no borra</h2>
 *
 * <p>El pago queda con {@code estado = ANULADO} y su motivo; la plata vuelve al cajon como una fila
 * propia {@code REVERSION_DE_EGRESO}, asentada por el <b>mismo</b> metodo de 07.03 —no hay un
 * camino de reversion nuevo—, y con eso vienen sus dos garantias: un movimiento se revierte una
 * sola vez, y la compensacion cae en la <b>jornada abierta hoy</b>, nunca reescribiendo una ya
 * arqueada.
 */
@Service
public class PagoEgresoService {

	private static final Logger log = LoggerFactory.getLogger(PagoEgresoService.class);

	private final EgresoRepositoryPort egresos;
	private final PagoEgresoRepositoryPort pagos;
	private final MovimientoCajaRepositoryPort movimientos;
	private final MovimientoCajaService movimientoService;
	private final CajaDeEgreso cajaDeEgreso;
	private final CajaAcceso acceso;
	private final AuditTrail auditTrail;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public PagoEgresoService(
			EgresoRepositoryPort egresos,
			PagoEgresoRepositoryPort pagos,
			MovimientoCajaRepositoryPort movimientos,
			MovimientoCajaService movimientoService,
			CajaDeEgreso cajaDeEgreso,
			CajaAcceso acceso,
			AuditTrail auditTrail) {

		this.egresos = egresos;
		this.pagos = pagos;
		this.movimientos = movimientos;
		this.movimientoService = movimientoService;
		this.cajaDeEgreso = cajaDeEgreso;
		this.acceso = acceso;
		this.auditTrail = auditTrail;
	}

	/**
	 * Paga un egreso confirmado, total o parcialmente (RF-M22-002).
	 *
	 * <p>La idempotencia se evalua <b>antes</b> de tocar ningun saldo, por lo mismo que 07.02 la
	 * evalua antes del numerador: un reintento no puede sacar la plata del cajon dos veces.
	 *
	 * @throws EgresoNoPagableException        el egreso es borrador, esta anulado o ya no debe (409)
	 * @throws EgresoSaldoInsuficienteException el pago excede lo que se debe (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PagoEgresoView pagar(
			OperatingActor actor, long consultorioId, long egresoId, PagoEgresoCommand command) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		ConsultorioSnapshot sede = acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		Optional<PagoEgreso> yaPagado = command.idempotencyKey() == null
				? Optional.empty()
				: pagos.findByIdempotencyKey(organizationId, command.idempotencyKey());
		if (yaPagado.isPresent()) {
			PagoEgreso existente = yaPagado.get();
			if (!command.huella(egresoId).equals(existente.getRequestHash())) {
				throw new IdempotencyKeyConflictException(command.idempotencyKey());
			}
			return PagoEgresoView.de(existente);
		}

		Egreso egreso = egresos.findByIdInScope(organizationId, consultorioId, egresoId)
				.orElseThrow(() -> new EgresoNotAccessibleException(egresoId));
		if (!egreso.admitePago()) {
			throw new EgresoNoPagableException(egresoId, motivoDeNoPagable(egreso));
		}

		// Se capturan ANTES del UPDATE nativo: `descontarSaldo` limpia el contexto de JPA y la
		// entidad queda desprendida. Leerla despues devolveria datos viejos, que es la trampa
		// que este repositorio ya pago.
		String moneda = egreso.getMoneda();
		BigDecimal importe = command.importe();
		Instant ahora = Instant.now();

		descontar(organizationId, consultorioId, egresoId, importe);
		egresos.actualizarEstadoPorSaldo(organizationId, egresoId);

		PagoEgreso pago = pagos.save(new PagoEgreso(
				organizationId, consultorioId, egresoId, importe, moneda,
				command.medio(), command.referencia(),
				CajaAcceso.fechaDeNegocio(sede, ahora),
				ahora, actor.accountId(),
				command.idempotencyKey(),
				command.idempotencyKey() == null ? null : command.huella(egresoId)));

		// Despues del pago y no antes: el movimiento apunta al pago por su id, y el id no existe
		// hasta que la fila esta insertada. Si el cajon no alcanza, esto lanza y la transaccion
		// entera revierte: no queda un pago huerfano describiendo plata que no salio.
		cajaDeEgreso.registrarSalida(
				organizationId, sede, pago.getId(), command.medio(), importe, moneda,
				"Pago de egreso " + egresoId, ahora, actor.accountId());

		auditar(actor, AuditEvents.EGRESO_PAGADO, pago, null, ahora);

		log.info("Egreso pagado: egresoId={} pagoId={} medio={} importe={}",
				egresoId, pago.getId(), command.medio(), importe);

		return PagoEgresoView.de(pago);
	}

	/**
	 * Deshace un pago y corrige la caja (RF-M22-005).
	 *
	 * <p>Tres efectos en la misma transaccion: el pago queda anulado —<b>no borrado</b>—, la plata
	 * vuelve al cajon como una reversion trazable, y el saldo vuelve al egreso.
	 *
	 * <p>Que la reversion la asiente {@code MovimientoCajaService.revertir} no es comodidad: de ahi
	 * vienen las dos garantias que esta operacion necesita y que no querria reimplementar — un
	 * movimiento se revierte <b>una sola vez</b>, y la compensacion cae en la jornada abierta hoy.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PagoEgresoView anularPago(
			OperatingActor actor, long consultorioId, long egresoId, long pagoId, String motivo) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		// El egreso se exige aunque no se use mas que para validar la ruta: pedir el pago de otro
		// egreso por la ruta de este devolveria un 200 con datos que no corresponden a la URL.
		egresos.findByIdInScope(organizationId, consultorioId, egresoId)
				.orElseThrow(() -> new EgresoNotAccessibleException(egresoId));

		PagoEgreso pago = pagos.findByIdInScope(organizationId, egresoId, pagoId)
				.orElseThrow(() -> new PagoEgresoNotAccessibleException(pagoId));

		Instant ahora = Instant.now();
		pago.anular(motivo, ahora, actor.accountId());
		pagos.save(pago);

		BigDecimal importe = pago.getImporte();

		// La plata vuelve al cajon. Si el movimiento no aparece, algo se rompio antes y es mejor
		// que la transaccion caiga que devolver el saldo sin devolver la plata.
		MovimientoCaja original = movimientos
				.findPorOrigen(organizationId, OrigenMovimiento.PAGO_EGRESO.name(), pagoId)
				.orElseThrow(() -> new IllegalStateException(
						"El pago " + pagoId + " no tiene movimiento de caja: el ledger y los pagos divergieron"));

		movimientoService.revertir(actor, consultorioId, original.getId(), motivo);

		// Y el saldo vuelve al egreso. Va DESPUES de la reversion para que un rechazo del cajon
		// —por ejemplo, revertir un egreso sin jornada abierta— no deje el saldo ya devuelto.
		egresos.devolverSaldo(organizationId, egresoId, importe);
		egresos.actualizarEstadoPorSaldo(organizationId, egresoId);

		auditar(actor, AuditEvents.EGRESO_PAGO_ANULADO, pago, motivo, ahora);

		log.info("Pago de egreso anulado: egresoId={} pagoId={} importe={} motivo={}",
				egresoId, pagoId, importe, motivo);

		return PagoEgresoView.de(pago);
	}

	// =================================================================================
	// Interno
	// =================================================================================

	/**
	 * El {@code UPDATE} condicional. Cero filas es la respuesta, no un error tecnico.
	 *
	 * <p>Cero filas tiene dos causas —el estado dejo de admitir el pago, o no alcanza el saldo— y
	 * hay que distinguirlas para responder bien. Se distinguen releyendo: es seguro porque un
	 * {@code UPDATE} de cero filas <b>no marca la transaccion para rollback</b>, a diferencia de un
	 * {@code flush} fallido por constraint, que si la marca y hace que cualquier consulta posterior
	 * termine en {@code UnexpectedRollbackException}.
	 */
	private void descontar(
			long organizationId, long consultorioId, long egresoId, BigDecimal importe) {

		if (egresos.descontarSaldo(organizationId, egresoId, importe) > 0) {
			return;
		}
		Egreso actual = egresos.findByIdInScope(organizationId, consultorioId, egresoId)
				.orElseThrow(() -> new EgresoNotAccessibleException(egresoId));
		if (!actual.getEstado().admitePago()) {
			throw new EgresoNoPagableException(egresoId, motivoDeNoPagable(actual));
		}
		throw new EgresoSaldoInsuficienteException(
				egresoId, importe, actual.getSaldoPendiente());
	}

	private static String motivoDeNoPagable(Egreso egreso) {
		return switch (egreso.getEstado()) {
			case BORRADOR -> "todavia es un borrador y no se confirmo";
			case ANULADO -> "esta anulado";
			case PAGADO -> "ya no tiene saldo pendiente";
			case CONFIRMADO -> "ya no tiene saldo pendiente";
		};
	}

	private void auditar(
			OperatingActor actor, String eventType, PagoEgreso pago, String motivo, Instant ahora) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("egresoId", String.valueOf(pago.getEgresoId()));
		detalles.put("medio", pago.getMedio().name());
		detalles.put("importe", pago.getImporte().toPlainString());
		detalles.put("estado", pago.getEstado().name());

		auditTrail.record(new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_PAGO_EGRESO,
				pago.getId(),
				null,
				null,
				detalles,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}
}
