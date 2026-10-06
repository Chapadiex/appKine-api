package com.akine.billing.application;

import com.akine.billing.domain.JornadaCaja;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.MovimientoCaja;
import com.akine.billing.domain.OrigenMovimiento;
import com.akine.billing.domain.TipoMovimiento;
import com.akine.billing.domain.exception.CajaCerradaException;
import com.akine.billing.domain.exception.CajaMonedaDistintaException;
import com.akine.billing.domain.exception.CajaNoAbiertaException;
import com.akine.billing.domain.exception.CajaSaldoInsuficienteException;
import com.akine.billing.domain.exception.JornadaCajaNotAccessibleException;
import com.akine.billing.domain.exception.MovimientoCajaNotAccessibleException;
import com.akine.billing.domain.exception.MovimientoNoReversibleException;
import com.akine.billing.domain.port.JornadaCajaRepositoryPort;
import com.akine.billing.domain.port.MovimientoCajaRepositoryPort;
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
 * Movimientos cargados a mano y compensaciones (RF-M20-002, 003, 004 y RF-M24-006).
 *
 * <h2>Un movimiento no se edita ni se borra: se compensa</h2>
 *
 * <p>El ledger es append-only y el puerto no declara {@code update} ni {@code delete}. Un error se
 * anula con una fila propia de tipo {@code REVERSION_DE_*}, con motivo obligatorio y puntero al
 * original, que es lo que RF-M24-006 pide poder mostrar.
 *
 * <p><b>Y la compensacion cae en la jornada que esta abierta HOY, nunca en la del original.</b> Es
 * la decision menos obvia de la etapa: la jornada original ya fue arqueada, y si el error afecto el
 * conteo su {@code diferencia} ya lo registro. Reescribirla haria que su saldo declarado dejara de
 * coincidir con lo que efectivamente se conto, destruyendo la unica evidencia de que hubo un
 * desvio. Y ademas, fisicamente, la plata sale o entra al cajon hoy.
 *
 * <h2>Estas operaciones si exigen caja abierta, a diferencia del cobro</h2>
 *
 * <p>Un cobro es un acto de venta y no se puede condicionar a que alguien se haya acordado de abrir
 * la caja —salvo cuando entra efectivo, que es el unico caso donde el mundo fisico obliga—. Un
 * movimiento manual y una reversion, en cambio, <b>son actos de operacion de caja</b>: no tienen
 * sentido fuera de una jornada.
 */
@Service
public class MovimientoCajaService {

	private static final Logger log = LoggerFactory.getLogger(MovimientoCajaService.class);

	private static final int LIMITE_MAXIMO = 200;

	private final MovimientoCajaRepositoryPort movimientos;
	private final JornadaCajaRepositoryPort jornadas;
	private final CajaAcceso acceso;
	private final AuditTrail auditTrail;

	public MovimientoCajaService(
			MovimientoCajaRepositoryPort movimientos,
			JornadaCajaRepositoryPort jornadas,
			CajaAcceso acceso,
			AuditTrail auditTrail) {

		this.movimientos = movimientos;
		this.jornadas = jornadas;
		this.acceso = acceso;
		this.auditTrail = auditTrail;
	}

	/**
	 * Carga un ingreso o un egreso que no nace de un cobro (RF-M20-002, RF-M20-003).
	 *
	 * <p>La idempotencia se evalua <b>antes</b> de tocar ningun saldo, por lo mismo que 07.02 la
	 * evalua antes del numerador: un reintento no puede mover el saldo dos veces.
	 *
	 * @throws CajaNoAbiertaException         la sede no tiene jornada abierta (409)
	 * @throws CajaSaldoInsuficienteException el egreso dejaria la caja en negativo (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public MovimientoCajaView registrarManual(
			OperatingActor actor, long consultorioId, MovimientoManualCommand command) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		if (command.tipo().esReversion()) {
			throw new IllegalArgumentException(
					"Una reversion no se carga como movimiento manual: exige el movimiento que compensa");
		}

		Optional<MovimientoCaja> yaRegistrado = command.idempotencyKey() == null
				? Optional.empty()
				: movimientos.findByIdempotencyKey(organizationId, command.idempotencyKey());
		if (yaRegistrado.isPresent()) {
			MovimientoCaja existente = yaRegistrado.get();
			if (!command.huella(consultorioId).equals(existente.getRequestHash())) {
				throw new IdempotencyKeyConflictException(command.idempotencyKey());
			}
			return MovimientoCajaView.de(existente);
		}

		JornadaCaja jornada = jornadaAbierta(organizationId, consultorioId);
		Instant ahora = Instant.now();

		MovimientoCaja movimiento = asentar(
				organizationId, consultorioId, jornada, jornada.getFechaNegocio(),
				command.tipo(), command.medio(), command.importe(), jornada.getMoneda(),
				command.concepto(), null,
				OrigenMovimiento.MANUAL, null, null,
				ahora, actor.accountId(),
				command.idempotencyKey(),
				command.idempotencyKey() == null ? null : command.huella(consultorioId));

		auditar(actor, AuditEvents.CAJA_MOVIMIENTO_MANUAL, movimiento, null, ahora);

		log.info("Movimiento manual de caja: movimientoId={} jornadaId={} tipo={} medio={} importe={}",
				movimiento.getId(), jornada.getId(), command.tipo(), command.medio(),
				command.importe());

		return MovimientoCajaView.de(movimiento);
	}

	/**
	 * Compensa un movimiento anterior (RN-M20-003, RF-M24-006).
	 *
	 * <p><b>No borra nada.</b> El movimiento original queda donde estaba, diciendo que ocurrio, y la
	 * compensacion se asienta en la jornada abierta hoy — que puede no ser la suya.
	 *
	 * @throws MovimientoNoReversibleException ya fue revertido, o es una reversion (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public MovimientoCajaView revertir(
			OperatingActor actor, long consultorioId, long movimientoId, String motivo) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		ConsultorioSnapshot sede = acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		MovimientoCaja original = movimientos
				.findByIdInScope(organizationId, consultorioId, movimientoId)
				.orElseThrow(() -> new MovimientoCajaNotAccessibleException(movimientoId));

		if (original.getTipo().esReversion()) {
			throw new MovimientoNoReversibleException(movimientoId,
					"ya es una reversion; para deshacerla se asienta un movimiento nuevo");
		}
		// El unique lo impide igualmente. Esto existe para poder explicarlo con un 409 legible en
		// vez de dejar que reviente una constraint, que ademas dejaria la transaccion marcada.
		if (movimientos.existeReversionDe(organizationId, movimientoId)) {
			throw new MovimientoNoReversibleException(movimientoId, "ya fue revertido");
		}

		// La jornada se exige SOLO si el movimiento original era en efectivo, que es lo que el
		// diseno de 07.03 §7 dice —"si no hay jornada abierta y el movimiento a revertir era en
		// efectivo, la reversion se rechaza"— y lo que su codigo NO hacia: la exigia siempre,
		// incluida la reversion de una transferencia, plata que nunca toco el cajon.
		//
		// No se noto en 07.03 porque alli las reversiones nacian de movimientos manuales, que ya
		// exigen caja abierta. Se nota en 07.05: anular un pago hecho por transferencia no puede
		// depender de que alguien haya abierto el cajon. El cambio AMPLIA lo aceptado y nunca lo
		// rechazado, asi que ningun caso que hoy funciona deja de funcionar.
		JornadaCaja jornada = original.afectaArqueo()
				? jornadaAbierta(organizationId, consultorioId)
				: jornadas.findAbierta(organizationId, consultorioId).orElse(null);

		Instant ahora = Instant.now();
		LocalDate fechaNegocio = jornada != null
				? jornada.getFechaNegocio()
				: CajaAcceso.fechaDeNegocio(sede, ahora);

		MovimientoCaja reversion = asentar(
				organizationId, consultorioId, jornada, fechaNegocio,
				original.getTipo().reversion(), original.getMedio(), original.getImporte(),
				original.getMoneda(),
				"Reversion del movimiento " + movimientoId, motivo,
				OrigenMovimiento.REVERSION, movimientoId, movimientoId,
				ahora, actor.accountId(), null, null);

		auditar(actor, AuditEvents.CAJA_MOVIMIENTO_REVERTIDO, reversion, motivo, ahora);

		// La jornada sale de la reversion y no de `jornada`, que es null cuando se revierte un
		// movimiento no-efectivo sin caja abierta: leerla aca tiraba NullPointerException y hacia
		// rollback de la anulacion entera, justo el caso que el cambio de arriba quiso habilitar.
		log.info("Movimiento de caja revertido: originalId={} reversionId={} jornadaId={} motivo={}",
				movimientoId, reversion.getId(), reversion.getJornadaCajaId(), motivo);

		return MovimientoCajaView.de(reversion);
	}

	/**
	 * La operatoria, filtrada (RF-M20-004).
	 *
	 * <p>Filtrar por dia y no solo por jornada es lo que hace visibles los movimientos
	 * <b>sin jornada</b> —los que no son en efectivo y llegaron sin caja abierta—, que de otro modo
	 * no aparecerian en ninguna vista.
	 */
	@Transactional(readOnly = true)
	public List<MovimientoCajaView> buscar(
			OperatingActor actor, long consultorioId, Long jornadaId,
			LocalDate fechaNegocio, String tipo, int limite, int desplazamiento) {

		long organizationId = CajaAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperarCaja(actor, organizationId, consultorioId);

		return movimientos.buscar(
						organizationId, consultorioId, jornadaId, fechaNegocio, tipo,
						Math.min(Math.max(limite, 1), LIMITE_MAXIMO), Math.max(desplazamiento, 0))
				.stream()
				.map(MovimientoCajaView::de)
				.toList();
	}

	// =================================================================================
	// Interno
	// =================================================================================

	private JornadaCaja jornadaAbierta(long organizationId, long consultorioId) {
		return jornadas.findAbierta(organizationId, consultorioId)
				.orElseThrow(() -> new CajaNoAbiertaException(consultorioId));
	}

	/**
	 * Mueve el saldo y asienta la fila, en ese orden y en la misma transaccion.
	 *
	 * <p>El saldo primero porque es la operacion que puede fallar por una condicion del motor: si no
	 * alcanza la plata, no queda una fila de ledger describiendo un egreso que no ocurrio.
	 *
	 * <p>Solo el efectivo mueve el saldo. Los demas medios asientan su fila igual —se listan y se
	 * totalizan— pero no tocan el arqueo: esa plata nunca estuvo en el cajon.
	 */
	@SuppressWarnings("checkstyle:ParameterNumber")
	MovimientoCaja asentar(
			long organizationId, long consultorioId, JornadaCaja jornada, LocalDate fechaNegocio,
			TipoMovimiento tipo, MedioDePago medio, BigDecimal importe, String moneda,
			String concepto, String motivo,
			OrigenMovimiento tipoOrigen, Long referenciaOrigen, Long movimientoOrigenId,
			Instant cuando, long actorCuentaId, String idempotencyKey, String requestHash) {

		Long jornadaId = jornada == null ? null : jornada.getId();

		if (medio == MedioDePago.EFECTIVO) {
			if (jornada == null) {
				throw new CajaNoAbiertaException(consultorioId);
			}
			if (!jornada.getMoneda().equals(moneda)) {
				throw new CajaMonedaDistintaException(jornada.getMoneda(), moneda);
			}
			moverSaldo(organizationId, consultorioId, jornada, tipo, importe);
		}

		return movimientos.save(new MovimientoCaja(
				organizationId, consultorioId, jornadaId, fechaNegocio,
				tipo, medio, importe, moneda, concepto, motivo,
				tipoOrigen, referenciaOrigen, movimientoOrigenId,
				cuando, actorCuentaId, idempotencyKey, requestHash));
	}

	/**
	 * El UPDATE condicional. Cero filas es la respuesta, no un error tecnico.
	 *
	 * <p>En la resta, cero filas tiene dos causas —la jornada cerro, o no alcanza el saldo— y hay
	 * que distinguirlas para responder bien. Se distinguen releyendo: es seguro porque un UPDATE de
	 * cero filas <b>no marca la transaccion para rollback</b>, a diferencia de un flush fallido por
	 * constraint, que si la marca y hace que cualquier consulta posterior termine en
	 * {@code UnexpectedRollbackException}.
	 */
	private void moverSaldo(
			long organizationId, long consultorioId, JornadaCaja jornada,
			TipoMovimiento tipo, BigDecimal importe) {

		long jornadaId = jornada.getId();
		int filas = tipo.signo() > 0
				? jornadas.sumarAlSaldo(organizationId, jornadaId, importe)
				: jornadas.restarDelSaldo(organizationId, jornadaId, importe);

		if (filas > 0) {
			return;
		}
		// En la SUMA, cero filas tiene una sola causa posible: el WHERE de sumarAlSaldo no mira el
		// saldo, y la jornada ya se encontro en el alcance antes de llegar aca, asi que lo unico
		// que puede haber fallado es `estado = 'ABIERTA'`. No se relee, y no es una optimizacion:
		// releer era el defecto. CobroService.registrar corre en REPEATABLE READ, y bajo esa
		// aislacion la relectura devuelve la foto de la primera lectura consistente —la jornada
		// todavia ABIERTA— aunque el UPDATE, que lee lo ultimo commiteado, ya la vio CERRADA. El
		// cobro que perdia la carrera contra el cierre terminaba en caja-saldo-insuficiente "para
		// egresar" sobre un ingreso. Lo destapo CierreDeCajaConcurrenteIT (escenario 34).
		if (tipo.signo() > 0) {
			throw new CajaCerradaException(jornadaId);
		}
		JornadaCaja actual = jornadas.findByIdInScope(organizationId, consultorioId, jornadaId)
				.orElseThrow(() -> new JornadaCajaNotAccessibleException(jornadaId));
		if (!actual.estaAbierta()) {
			throw new CajaCerradaException(jornadaId);
		}
		throw new CajaSaldoInsuficienteException(jornadaId, importe, actual.getSaldoArqueo());
	}

	private void auditar(
			OperatingActor actor, String eventType, MovimientoCaja movimiento,
			String motivo, Instant ahora) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("tipo", movimiento.getTipo().name());
		detalles.put("medio", movimiento.getMedio().name());
		detalles.put("importe", movimiento.getImporte().toPlainString());
		detalles.put("afectaArqueo", String.valueOf(movimiento.afectaArqueo()));
		if (movimiento.getJornadaCajaId() != null) {
			detalles.put("jornadaCajaId", String.valueOf(movimiento.getJornadaCajaId()));
		}
		if (movimiento.getMovimientoOrigenId() != null) {
			detalles.put("movimientoOrigenId", String.valueOf(movimiento.getMovimientoOrigenId()));
		}

		auditTrail.record(new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_MOVIMIENTO_CAJA,
				movimiento.getId(),
				null,
				null,
				detalles,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}
}
