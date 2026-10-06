package com.akine.billing.application;

import com.akine.billing.domain.Cobro;
import com.akine.billing.domain.CobroImputacion;
import com.akine.billing.domain.CobroReintegro;
import com.akine.billing.domain.MovimientoCaja;
import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.OrigenMovimiento;
import com.akine.billing.domain.PermissionCodes;
import com.akine.billing.domain.TipoMovimiento;
import com.akine.billing.domain.exception.CobroAnuladoException;
import com.akine.billing.domain.exception.CobroConReintegrosException;
import com.akine.billing.domain.exception.CobroNotAccessibleException;
import com.akine.billing.domain.exception.ObligacionNoCobrableException;
import com.akine.billing.domain.exception.SaldoAFavorInsuficienteException;
import com.akine.billing.domain.exception.SaldoInsuficienteException;
import com.akine.billing.domain.port.CobroReintegroRepositoryPort;
import com.akine.billing.domain.port.CobroRepositoryPort;
import com.akine.billing.domain.port.MovimientoCajaRepositoryPort;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lo que se hace sobre un cobro ya registrado (M19, paquete F-3): imputar su saldo a favor,
 * reintegrarlo y anular el cobro.
 *
 * <h2>Las tres toman el lock del cobro, y es lo que las hace correctas</h2>
 *
 * <p>Compiten por el mismo saldo a favor y por el mismo estado. Sin un lock, dos imputaciones del
 * mismo anticipo leen el mismo saldo y pasan las dos, y una anulacion lee las imputaciones antes de
 * que una concurrente commitee y devuelve saldo a la deuda que nunca se desconto. Con
 * {@code SELECT ... FOR UPDATE} del cobro las tres se serializan, y la transaccion va en
 * {@code READ_COMMITTED} para que lo que se lee despues del lock sea lo que dejo la anterior y no
 * la foto de antes (regla de 05.02, extendida en la integracion del 29/09). El {@code CHECK} de
 * {@code saldo_a_favor} sostiene el rango aunque el lock fallara.
 *
 * <p>El lock es la <b>primera carga del cobro</b> en cada operacion. Si la entidad ya estuviera en
 * la sesion, Hibernate devolveria la instancia vieja sin refrescarla y el lock no serviria de nada:
 * por eso la idempotencia de la imputacion se resuelve con una consulta que devuelve un id y no una
 * entidad, y despues del lock.
 *
 * <h2>Deuda, cobro y caja siguen siendo tres cosas</h2>
 *
 * <ul>
 *   <li><b>Imputar</b> mueve el saldo a favor del cobro y el saldo de la deuda. <b>No toca la
 *       caja</b>: la plata entro cuando se cobro.</li>
 *   <li><b>Reintegrar</b> mueve el saldo a favor y la caja. <b>No toca ninguna deuda.</b></li>
 *   <li><b>Anular</b> toca las tres, cada una con su primitivo: la deuda con un UPDATE condicional,
 *       el cobro con su flag y la caja con reversiones de {@link MovimientoCajaService#revertir}.
 *       Nada se borra.</li>
 * </ul>
 *
 * <h2>Permisos</h2>
 *
 * <p>Imputar exige {@code cobro:register}, como cobrar. Anular y reintegrar mueven plata del cajon,
 * asi que exigen ademas {@code caja:operate}: es el "permiso reforzado" que el plan pide para las
 * anulaciones, sin inventar un codigo que la matriz §32 no tiene.
 */
@Service
public class CobroPosteriorService {

	private static final Logger log = LoggerFactory.getLogger(CobroPosteriorService.class);

	private final CobroRepositoryPort cobros;
	private final ObligacionRepositoryPort obligaciones;
	private final CobroReintegroRepositoryPort reintegros;
	private final MovimientoCajaRepositoryPort movimientos;
	private final MovimientoCajaService movimientoService;
	private final CajaDeCobro caja;
	private final CajaAcceso acceso;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public CobroPosteriorService(
			CobroRepositoryPort cobros,
			ObligacionRepositoryPort obligaciones,
			CobroReintegroRepositoryPort reintegros,
			MovimientoCajaRepositoryPort movimientos,
			MovimientoCajaService movimientoService,
			CajaDeCobro caja,
			CajaAcceso acceso,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.cobros = cobros;
		this.obligaciones = obligaciones;
		this.reintegros = reintegros;
		this.movimientos = movimientos;
		this.movimientoService = movimientoService;
		this.caja = caja;
		this.acceso = acceso;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	/**
	 * Aplica parte del saldo a favor de un cobro a una deuda (RF-M19-003, imputacion posterior).
	 *
	 * <p>No mueve caja. La deuda tiene que ser de la sede del cobro y de su misma persona, admitir
	 * cobro y estar en su moneda: las mismas reglas que al cobrar.
	 *
	 * @throws CobroNotAccessibleException      el cobro no existe en esa sede o es de otro tenant (404)
	 * @throws CobroAnuladoException            el cobro esta anulado (409)
	 * @throws SaldoAFavorInsuficienteException no queda tanto a favor (409)
	 * @throws ObligacionNoCobrableException    deuda inexistente, ajena, pagada, anulada, en otra
	 *                                          moneda, o ya imputada por este cobro (409)
	 * @throws SaldoInsuficienteException       la deuda ya no debe tanto (409)
	 * @throws IdempotencyKeyConflictException  misma clave, pedido distinto (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CobroView imputarSaldoAFavor(
			OperatingActor actor, long consultorioId, long cobroId, ImputacionPosteriorCommand command) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		exigirPermiso(actor, PermissionCodes.COBRO_REGISTER, organizationId, consultorioId);

		Cobro cobro = bloquear(organizationId, consultorioId, cobroId);

		// Idempotencia DESPUES del lock: el perdedor de una carrera con la misma clave espera al
		// ganador y, en READ_COMMITTED, ve su fila.
		String clave = command.idempotencyKey();
		String huella = clave == null ? null : command.huella(consultorioId, cobroId);
		if (clave != null) {
			Optional<Long> cobroDeLaClave = cobros.cobroDeLaImputacionConClave(organizationId, clave);
			if (cobroDeLaClave.isPresent()) {
				CobroImputacion previa = cobroDeLaClave.get() == cobroId
						? cobro.imputacionConClave(clave).orElse(null)
						: null;
				if (previa == null || !huella.equals(previa.getRequestHash())) {
					throw new IdempotencyKeyConflictException(clave);
				}
				return CobroView.de(cobro);
			}
		}

		Obligacion obligacion = obligaciones
				.findByIdInScope(organizationId, consultorioId, command.obligacionId())
				.orElseThrow(() -> new ObligacionNoCobrableException(
						command.obligacionId(), "no existe en esta sede"));
		CobroService.exigirCobrable(obligacion, cobro.getPersonaId());
		CobroService.exigirMonedaUnica(cobro.getMoneda(), obligacion);

		Instant ahora = Instant.now();
		// El cobro primero: si el anticipo no alcanza, la deuda no se toca.
		cobro.imputar(CobroImputacion.posterior(
				organizationId, obligacion.getId(), command.importe(),
				ahora, actor.accountId(), clave, huella));

		if (cobros.descontarSaldo(organizationId, obligacion.getId(), command.importe()) == 0) {
			throw new SaldoInsuficienteException(obligacion.getId(), command.importe());
		}
		cobros.actualizarEstadoPorSaldo(organizationId, obligacion.getId());
		cobros.flush();

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("obligacionId", String.valueOf(obligacion.getId()));
		detalles.put("importe", command.importe().toPlainString());
		detalles.put("saldoAFavorRestante", cobro.getSaldoAFavor().toPlainString());
		auditar(actor, AuditEvents.COBRO_SALDO_IMPUTADO, cobro, detalles, null, ahora);

		log.info("Saldo a favor imputado: cobroId={} obligacionId={} importe={} restante={}",
				cobroId, obligacion.getId(), command.importe(), cobro.getSaldoAFavor());

		return CobroView.de(cobro);
	}

	/**
	 * Anula un cobro y revierte sus efectos (RF-M19-007, RN-M19-004).
	 *
	 * <p>En una transaccion: cada imputacion devuelve su importe a su deuda —que vuelve a
	 * {@code PENDIENTE} o {@code PARCIAL}—, cada movimiento de caja del cobro se revierte en la
	 * jornada abierta <b>hoy</b>, y el cobro queda anulado con actor y motivo. El comprobante no se
	 * libera: su numero queda usado.
	 *
	 * @throws CobroAnuladoException         ya estaba anulado (409)
	 * @throws CobroConReintegrosException   ya devolvio parte de su saldo a favor (409)
	 * @throws com.akine.billing.domain.exception.CajaNoAbiertaException          habia efectivo y no
	 *                                       hay caja abierta (409)
	 * @throws com.akine.billing.domain.exception.CajaSaldoInsuficienteException  el cajon no tiene
	 *                                       la plata para devolver (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public CobroView anular(OperatingActor actor, long consultorioId, long cobroId, String motivo) {
		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		exigirPermiso(actor, PermissionCodes.COBRO_REGISTER, organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		Cobro cobro = bloquear(organizationId, consultorioId, cobroId);
		if (cobro.estaAnulado()) {
			throw new CobroAnuladoException(cobroId);
		}
		if (reintegros.existenDelCobro(organizationId, cobroId)) {
			throw new CobroConReintegrosException(cobroId);
		}

		// 1. La deuda. Cero filas no es una carrera de negocio —la deuda no puede haberse anulado
		// con un cobro imputado, ni tener mas saldo que su importe— sino una divergencia entre el
		// cobro y la deuda, y es mejor que la transaccion caiga a que la deuda quede a medias.
		for (CobroImputacion imputacion : cobro.getImputaciones()) {
			if (cobros.devolverSaldo(organizationId, imputacion.getObligacionId(), imputacion.getImporte()) == 0) {
				throw new IllegalStateException("La obligacion " + imputacion.getObligacionId()
						+ " no admite que se le devuelvan " + imputacion.getImporte()
						+ " del cobro " + cobroId + ": el cobro y la deuda divergieron");
			}
		}

		// 2. El cobro, y escrito YA. No es estilo: los UPDATE de la jornada de caja llevan
		// clearAutomatically, asi que despues de revertir un movimiento en efectivo el cobro queda
		// fuera de la sesion y una marca hecha despues no llegaria nunca a la base —la primera
		// corrida del IT lo encontro: deuda devuelta, caja revertida y cobro vigente—. El lock de
		// fila sigue tomado: limpiar la sesion no lo suelta.
		Instant ahora = Instant.now();
		cobro.anular(motivo, ahora, actor.accountId());
		cobros.flush();

		// 3. La caja. Una reversion por movimiento: revertir sabe que la compensacion cae en la
		// jornada abierta hoy, que el efectivo la exige y que un movimiento se revierte una vez. Si
		// la rechaza, la transaccion entera vuelve atras, marca del cobro incluida.
		List<MovimientoCaja> ingresos = movimientos
				.findTodosPorOrigen(organizationId, OrigenMovimiento.COBRO.name(), cobroId).stream()
				.filter(movimiento -> movimiento.getTipo() == TipoMovimiento.INGRESO)
				.toList();
		for (MovimientoCaja ingreso : ingresos) {
			movimientoService.revertir(actor, consultorioId, ingreso.getId(), motivo);
		}

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("comprobanteNumero", String.valueOf(cobro.getComprobanteNumero()));
		detalles.put("total", cobro.getTotal().toPlainString());
		detalles.put("imputacionesRevertidas", String.valueOf(cobro.getImputaciones().size()));
		detalles.put("movimientosRevertidos", String.valueOf(ingresos.size()));
		auditar(actor, AuditEvents.COBRO_ANULADO, cobro, detalles, motivo, ahora);

		log.info("Cobro anulado: cobroId={} comprobante={} imputaciones={} movimientos={} motivo={}",
				cobroId, cobro.getComprobanteNumero(), cobro.getImputaciones().size(),
				ingresos.size(), motivo);

		return CobroView.de(cobro);
	}

	/**
	 * Devuelve en dinero parte del saldo a favor de un cobro (DP-06/ADR-0013).
	 *
	 * <p>Descuenta el saldo a favor, deja una fila de reintegro y asienta un {@code EGRESO} de caja
	 * de origen {@code REINTEGRO}, en una transaccion. El efectivo exige caja abierta y plata en el
	 * cajon; lo demas no.
	 *
	 * @throws CobroAnuladoException            el cobro esta anulado (409)
	 * @throws SaldoAFavorInsuficienteException no queda tanto a favor (409)
	 * @throws IdempotencyKeyConflictException  misma clave, pedido distinto (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ReintegroView reintegrar(
			OperatingActor actor, long consultorioId, long cobroId, ReintegroCommand command) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		ConsultorioSnapshot sede = acceso.exigirSedeDelTenant(organizationId, consultorioId);
		exigirPermiso(actor, PermissionCodes.COBRO_REGISTER, organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		Cobro cobro = bloquear(organizationId, consultorioId, cobroId);

		String clave = command.idempotencyKey();
		String huella = clave == null ? null : command.huella(consultorioId, cobroId);
		if (clave != null) {
			Optional<CobroReintegro> previo = reintegros.findByIdempotencyKey(organizationId, clave);
			if (previo.isPresent()) {
				if (!previo.get().getCobroId().equals(cobroId)
						|| !huella.equals(previo.get().getRequestHash())) {
					throw new IdempotencyKeyConflictException(clave);
				}
				return ReintegroView.de(previo.get(), cobro.getSaldoAFavor());
			}
		}

		Instant ahora = Instant.now();
		cobro.reintegrar(command.importe());
		// Escrito antes de la caja por lo mismo que en la anulacion: el asiento en efectivo limpia
		// la sesion y el cobro quedaria afuera con su saldo viejo en la base.
		cobros.flush();
		CobroReintegro reintegro = reintegros.save(new CobroReintegro(
				cobro, command.importe(), command.medio(), command.referencia(), command.motivo(),
				ahora, actor.accountId(), clave, huella));

		caja.registrarReintegro(
				organizationId, sede, reintegro.getId(), cobroId, command.medio(),
				command.importe(), cobro.getMoneda(), ahora, actor.accountId());

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("reintegroId", String.valueOf(reintegro.getId()));
		detalles.put("importe", command.importe().toPlainString());
		detalles.put("medio", command.medio().name());
		detalles.put("saldoAFavorRestante", cobro.getSaldoAFavor().toPlainString());
		auditar(actor, AuditEvents.COBRO_REINTEGRADO, cobro, detalles, reintegro.getMotivo(), ahora);

		log.info("Saldo a favor reintegrado: cobroId={} reintegroId={} importe={} medio={} restante={}",
				cobroId, reintegro.getId(), command.importe(), command.medio(), cobro.getSaldoAFavor());

		return ReintegroView.de(reintegro, cobro.getSaldoAFavor());
	}

	// =================================================================================
	// Interno
	// =================================================================================

	private Cobro bloquear(long organizationId, long consultorioId, long cobroId) {
		return cobros.findByIdInScopeParaEscribir(organizationId, consultorioId, cobroId)
				.orElseThrow(() -> new CobroNotAccessibleException(cobroId));
	}

	private void exigirPermiso(
			OperatingActor actor, String permiso, long organizationId, long consultorioId) {

		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), permiso, organizationId, consultorioId, null, Instant.now()));
	}

	private void auditar(
			OperatingActor actor, String eventType, Cobro cobro, Map<String, String> detalles,
			String motivo, Instant ahora) {

		auditTrail.record(new AuditEntry(
				actor.contextOrganizationId(),
				cobro.getConsultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_COBRO,
				cobro.getId(),
				null,
				null,
				detalles,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}
}
