package com.akine.billing.application;

import com.akine.billing.domain.FinanciadorPago;
import com.akine.billing.domain.JornadaCaja;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.MovimientoCaja;
import com.akine.billing.domain.OrigenMovimiento;
import com.akine.billing.domain.Presentacion;
import com.akine.billing.domain.TipoMovimiento;
import com.akine.billing.domain.exception.PresentacionEstadoInvalidoException;
import com.akine.billing.domain.exception.PresentacionNotAccessibleException;
import com.akine.billing.domain.exception.PresentacionSaldoInsuficienteException;
import com.akine.billing.domain.port.FinanciadorPagoRepositoryPort;
import com.akine.billing.domain.port.JornadaCajaRepositoryPort;
import com.akine.billing.domain.port.PresentacionRepositoryPort;
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
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * El pago del financiador (RF-M21-007): el <b>unico</b> punto donde M21 toca la caja.
 *
 * <h2>RN-M21-002, y por que el asiento va en la misma transaccion</h2>
 *
 * <p>"El pago del financiador genera caja solo cuando se recibe". Presentar no genera caja,
 * facturar no genera caja: esto si. Y el movimiento se asienta <b>dentro</b> de la transaccion que
 * registra el pago, por lo mismo que {@code CajaDeCobro} lo hace con los cobros: si se dejara para
 * despues, nada obligaria a que alguien lo hiciera, nada verificaria el importe, y la caja y la
 * cuenta corriente divergirian <b>sin que falle nada</b>. La contrapartida esta asumida: si el
 * asiento falla, el pago no se registra.
 *
 * <h2>Esto no salda ninguna obligacion, y es deliberado</h2>
 *
 * <p>Un pago es un importe global y no viene con el detalle de que prestaciones cubre. Repartirlo
 * entre los items exigiria una regla de imputacion que el financiador no informo, y produciria
 * obligaciones marcadas como pagadas que el nunca acepto. Las obligaciones se saldan al
 * <b>conciliar</b>, item por item y por su importe exacto: ver {@code PresentacionService}.
 *
 * <h2>El transporte y el arqueo</h2>
 *
 * <p>Casi siempre el medio es {@code TRANSFERENCIA}, y entonces el movimiento se asienta con
 * {@code jornada_caja_id} NULL y no afecta el arqueo: esa plata fue a un banco, no al cajon. Es la
 * regla de 07.03 aplicada sin excepcion, y meterla en el arqueo garantizaria que el conteo nunca
 * cuadre. Si el financiador paga en efectivo —raro pero legitimo— rige la otra mitad de esa regla:
 * hace falta jornada abierta, o 409 {@code caja-no-abierta}.
 */
@Service
public class FinanciadorPagoService {

	private static final Logger log = LoggerFactory.getLogger(FinanciadorPagoService.class);

	private final FinanciadorPagoRepositoryPort pagos;
	private final PresentacionRepositoryPort presentaciones;
	private final JornadaCajaRepositoryPort jornadas;
	private final MovimientoCajaService movimientos;
	private final PresentacionAcceso acceso;
	private final AuditTrail auditTrail;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public FinanciadorPagoService(
			FinanciadorPagoRepositoryPort pagos,
			PresentacionRepositoryPort presentaciones,
			JornadaCajaRepositoryPort jornadas,
			MovimientoCajaService movimientos,
			PresentacionAcceso acceso,
			AuditTrail auditTrail) {

		this.pagos = pagos;
		this.presentaciones = presentaciones;
		this.jornadas = jornadas;
		this.movimientos = movimientos;
		this.acceso = acceso;
		this.auditTrail = auditTrail;
	}

	/**
	 * Registra la plata que el financiador pago contra un lote.
	 *
	 * <p>La idempotencia se evalua <b>antes</b> de tocar ningun saldo, por lo mismo que 07.02 la
	 * evalua antes del numerador: un reintento no puede mover el saldo dos veces.
	 *
	 * @throws PresentacionSaldoInsuficienteException el pago supera lo que queda por explicar (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public FinanciadorPagoView registrar(
			OperatingActor actor, long consultorioId, long presentacionId,
			PresentacionCommands.Pago command) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		ConsultorioSnapshot sede = acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);

		Optional<FinanciadorPago> yaRegistrado = command.idempotencyKey() == null
				? Optional.empty()
				: pagos.findByIdempotencyKey(organizationId, command.idempotencyKey());
		if (yaRegistrado.isPresent()) {
			FinanciadorPago existente = yaRegistrado.get();
			if (!command.huella(presentacionId).equals(existente.getRequestHash())) {
				throw new IdempotencyKeyConflictException(command.idempotencyKey());
			}
			return FinanciadorPagoView.de(existente, null);
		}

		Presentacion presentacion = presentaciones
				.findByIdInScope(organizationId, consultorioId, presentacionId)
				.orElseThrow(() -> new PresentacionNotAccessibleException(presentacionId));

		// El saldo se mueve PRIMERO: es la operacion que puede fallar por una condicion del motor,
		// y si no alcanza no queda un pago registrado contra un lote que no lo admitia.
		int filas = presentaciones.registrarCobro(
				organizationId, presentacionId, command.importe());
		if (filas == 0) {
			rechazar(organizationId, consultorioId, presentacionId, command.importe());
		}

		Instant ahora = Instant.now();
		FinanciadorPago pago = pagos.save(new FinanciadorPago(
				organizationId, consultorioId, presentacion.getFinanciadorId(), presentacionId,
				command.importe(), presentacion.getMoneda(), command.medio(),
				command.fechaPago(), command.referencia(), ahora, actor.accountId(),
				command.idempotencyKey(),
				command.idempotencyKey() == null ? null : command.huella(presentacionId)));

		MovimientoCaja movimiento = asentarEnCaja(
				organizationId, sede, presentacion, pago, ahora, actor.accountId());

		auditar(actor, pago, presentacion, movimiento, ahora);
		log.info("Pago de financiador registrado: pagoId={} presentacionId={} importe={} medio={} "
						+ "movimientoId={}",
				pago.getId(), presentacionId, command.importe(), command.medio(),
				movimiento.getId());

		return FinanciadorPagoView.de(pago, movimiento.getId());
	}

	/** Los pagos de un lote, para su detalle. */
	@Transactional(readOnly = true)
	public List<FinanciadorPagoView> deLaPresentacion(
			OperatingActor actor, long consultorioId, long presentacionId) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);

		presentaciones.findByIdInScope(organizationId, consultorioId, presentacionId)
				.orElseThrow(() -> new PresentacionNotAccessibleException(presentacionId));

		return pagos.findDeLaPresentacion(presentacionId).stream()
				.map(pago -> FinanciadorPagoView.de(pago, null))
				.toList();
	}

	/**
	 * La cuenta corriente de un financiador: lo reclamado, lo debitado, lo cobrado y el saldo.
	 *
	 * <p><b>Cruza sedes a proposito.</b> La relacion comercial es de la <b>organizacion</b> aunque
	 * cada lote se arme en una sede, y un centro con dos consultorios negocia una sola cuenta con
	 * cada obra social.
	 *
	 * <p><b>Y esto no es la caja.</b> La caja es un cajon con jornada y arqueo; esto es una relacion
	 * que vive en meses y que nadie cuenta. El unico lugar donde se tocan es el pago.
	 */
	@Transactional(readOnly = true)
	public CuentaCorrienteDeFinanciador cuentaCorriente(
			OperatingActor actor, long consultorioId, long financiadorId) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);
		acceso.exigirFinanciador(organizationId, financiadorId);

		BigDecimal presentado = BigDecimal.ZERO.setScale(2);
		BigDecimal debitado = BigDecimal.ZERO.setScale(2);
		BigDecimal cobrado = BigDecimal.ZERO.setScale(2);
		BigDecimal saldo = BigDecimal.ZERO.setScale(2);

		List<Presentacion> lotes = presentaciones.buscar(
				organizationId, consultorioId, null, financiadorId, null, null, 200, 0);
		for (Presentacion lote : lotes) {
			presentado = presentado.add(lote.getTotalPresentado());
			debitado = debitado.add(lote.getTotalDebitado());
			cobrado = cobrado.add(lote.getTotalCobrado());
			saldo = saldo.add(lote.getSaldo());
		}

		return new CuentaCorrienteDeFinanciador(
				financiadorId, acceso.nombreDe(organizationId, financiadorId),
				presentado, debitado, cobrado, saldo, lotes.size());
	}

	// =================================================================================
	// Interno
	// =================================================================================

	/**
	 * El asiento en caja, con la regla de 07.03 sin excepciones.
	 *
	 * <p>Reusa {@code MovimientoCajaService.asentar}, que ya sabe que solo el efectivo mueve el
	 * arqueo y que solo el efectivo exige jornada. Escribir una segunda version aqui habria sido la
	 * forma mas rapida de que las dos divergieran.
	 */
	private MovimientoCaja asentarEnCaja(
			long organizationId, ConsultorioSnapshot sede, Presentacion presentacion,
			FinanciadorPago pago, Instant ahora, long actorCuentaId) {

		JornadaCaja jornada = jornadas.findAbierta(organizationId, sede.id()).orElse(null);
		LocalDate fechaNegocio = jornada != null
				? jornada.getFechaNegocio()
				: pago.getFechaPago();

		return movimientos.asentar(
				organizationId, sede.id(), jornada, fechaNegocio,
				TipoMovimiento.INGRESO, pago.getMedio(), pago.getImporte(),
				presentacion.getMoneda(),
				"Pago de financiador - presentacion " + presentacion.getId(), null,
				OrigenMovimiento.PAGO_FINANCIADOR, pago.getId(), null,
				ahora, actorCuentaId, null, null);
	}

	/**
	 * Traduce cero filas del {@code UPDATE} condicional.
	 *
	 * <p>Releer es seguro: un {@code UPDATE} de cero filas no marca la transaccion para rollback, a
	 * diferencia de un {@code flush} fallido por constraint. Las dos causas —el estado cambio, o el
	 * importe no entra en el saldo— mandan al operador a lugares distintos y hay que distinguirlas.
	 */
	private void rechazar(
			long organizationId, long consultorioId, long presentacionId, BigDecimal importe) {

		Presentacion actual = presentaciones
				.findByIdInScope(organizationId, consultorioId, presentacionId)
				.orElseThrow(() -> new PresentacionNotAccessibleException(presentacionId));

		if (!actual.getEstado().estaEnCurso()) {
			throw new PresentacionEstadoInvalidoException(
					presentacionId, actual.getEstado().name(), "PRESENTADA o FACTURADA");
		}
		throw new PresentacionSaldoInsuficienteException(
				presentacionId, importe, actual.getSaldo());
	}

	/** La auditoria se escribe DENTRO de la transaccion del negocio, nunca post-commit. */
	private void auditar(
			OperatingActor actor, FinanciadorPago pago, Presentacion presentacion,
			MovimientoCaja movimiento, Instant ahora) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("presentacionId", String.valueOf(presentacion.getId()));
		detalles.put("financiadorId", String.valueOf(pago.getFinanciadorId()));
		detalles.put("importe", pago.getImporte().toPlainString());
		detalles.put("medio", pago.getMedio().name());
		detalles.put("movimientoCajaId", String.valueOf(movimiento.getId()));
		detalles.put("afectaArqueo", String.valueOf(pago.getMedio() == MedioDePago.EFECTIVO));

		auditTrail.record(new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				AuditEvents.PRESENTACION_PAGO_REGISTRADO,
				AuditEvents.ENTITY_PRESENTACION,
				presentacion.getId(),
				null,
				null,
				detalles,
				null,
				AuditEvents.correlationId(),
				ahora));
	}
}
