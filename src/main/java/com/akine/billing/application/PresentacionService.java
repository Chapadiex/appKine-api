package com.akine.billing.application;

import com.akine.billing.domain.Obligacion;
import com.akine.billing.domain.Presentacion;
import com.akine.billing.domain.PresentacionItem;
import com.akine.billing.domain.Responsable;
import com.akine.billing.domain.SnapshotDeConvenio;
import com.akine.billing.domain.exception.FacturaDuplicadaException;
import com.akine.billing.domain.exception.ObligacionNoPresentableException;
import com.akine.billing.domain.exception.ObligacionNotAccessibleException;
import com.akine.billing.domain.exception.ObligacionYaPresentadaException;
import com.akine.billing.domain.exception.PresentacionConHallazgosException;
import com.akine.billing.domain.exception.PresentacionEstadoInvalidoException;
import com.akine.billing.domain.exception.PresentacionItemNotAccessibleException;
import com.akine.billing.domain.exception.PresentacionNoConciliaException;
import com.akine.billing.domain.exception.PresentacionNoEditableException;
import com.akine.billing.domain.exception.PresentacionNotAccessibleException;
import com.akine.billing.domain.exception.PresentacionSaldoInsuficienteException;
import com.akine.billing.domain.exception.PresentacionVaciaException;
import com.akine.billing.domain.port.CobroRepositoryPort;
import com.akine.billing.domain.port.ObligacionRepositoryPort;
import com.akine.billing.domain.port.PresentacionItemRepositoryPort;
import com.akine.billing.domain.port.PresentacionNumeradorPort;
import com.akine.billing.domain.port.PresentacionRepositoryPort;
import com.akine.contracting.spi.FinanciadorSnapshot;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.person.spi.RespaldoDocumentalDirectory;
import com.akine.person.spi.RespaldoDocumentalDirectory.RespaldoDocumental;
import com.akine.platform.spi.listado.TextoDeBusqueda;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.transaccion.FilaLockPrevia;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * El ciclo completo de un lote reclamado a un financiador (M21).
 *
 * <h2>Lo que este servicio NO hace, y es la mitad del diseño</h2>
 *
 * <p><b>Ningun comando salda deuda y mueve caja a la vez.</b> Se puede verificar leyendo los siete:
 * {@code crear}, {@code agregarItem}, {@code quitarItem}, {@code validar}, {@code confirmar},
 * {@code registrarFactura} y {@code anular} no tocan ni el saldo de una obligacion ni la caja.
 * {@code conciliar} salda obligaciones y no toca caja; y la caja la toca un servicio distinto,
 * {@code FinanciadorPagoService}, que no salda ninguna obligacion.
 *
 * <p>Esa separacion es RN-M21-001 hecha cumplir por lo que el codigo no hace: prestado, presentado,
 * facturado y cobrado son cuatro estados distintos, y confirmar un lote de doscientas sesiones no
 * mueve un peso.
 *
 * <h2>Las mutaciones van en READ_COMMITTED</h2>
 *
 * <p>Misma regla que todas las mutaciones que serializan en este repositorio: con
 * {@code REPEATABLE READ} InnoDB fija la foto en la primera lectura consistente, que ocurre antes
 * de la escritura condicional, y el {@code UPDATE ... WHERE saldo >= :importe} dejaria de ver lo
 * que otra transaccion acaba de cometer.
 */
@Service
public class PresentacionService {

	private static final Logger log = LoggerFactory.getLogger(PresentacionService.class);

	private static final int LIMITE_MAXIMO = 200;

	private final PresentacionRepositoryPort presentaciones;
	private final PresentacionItemRepositoryPort items;
	private final ObligacionRepositoryPort obligaciones;
	private final CobroRepositoryPort cobros;
	private final PresentacionNumeradorPort numerador;
	private final PresentacionNumeradorIniciador numeradorIniciador;
	private final ObligacionOcupadaConsulta ocupacion;
	private final PresentacionAcceso acceso;
	private final AuditTrail auditTrail;
	private final RespaldoDocumentalDirectory respaldos;
	private final TransactionTemplate transaccionReadCommitted;

	@SuppressWarnings("checkstyle:ParameterNumber")
	public PresentacionService(
			PresentacionRepositoryPort presentaciones,
			PresentacionItemRepositoryPort items,
			ObligacionRepositoryPort obligaciones,
			CobroRepositoryPort cobros,
			PresentacionNumeradorPort numerador,
			PresentacionNumeradorIniciador numeradorIniciador,
			ObligacionOcupadaConsulta ocupacion,
			PresentacionAcceso acceso,
			AuditTrail auditTrail,
			RespaldoDocumentalDirectory respaldos,
			PlatformTransactionManager transactionManager) {

		this.presentaciones = presentaciones;
		this.items = items;
		this.obligaciones = obligaciones;
		this.cobros = cobros;
		this.numerador = numerador;
		this.numeradorIniciador = numeradorIniciador;
		this.ocupacion = ocupacion;
		this.acceso = acceso;
		this.auditTrail = auditTrail;
		this.respaldos = respaldos;
		this.transaccionReadCommitted = FilaLockPrevia.transaccionDeNegocio(
				transactionManager, Isolation.READ_COMMITTED);
	}

	// =================================================================================
	// Armado del lote
	// =================================================================================

	/**
	 * Las prestaciones que se le pueden reclamar a un financiador en un periodo (RF-M21-001).
	 *
	 * <p>Hasta AKINE F-4 devolvia lista vacia en cualquier despliegue real: nada devengaba
	 * obligaciones con responsable {@code FINANCIADOR}. Desde F-4 las devenga el cierre de cada
	 * sesion cubierta por un convenio, y cada una trae el convenio congelado con sus requisitos.
	 */
	@Transactional(readOnly = true)
	public List<ObligacionView> elegibles(
			OperatingActor actor, long consultorioId, long financiadorId,
			LocalDate desde, LocalDate hasta, int limite, int desplazamiento) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		ConsultorioSnapshot sede = acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);
		acceso.exigirFinanciador(organizationId, financiadorId);

		// El periodo es en dias de la sede, como el `fecha_prestacion` que congela `agregarItem`.
		return obligaciones.findElegiblesParaPresentar(
						organizationId, consultorioId, financiadorId,
						CajaAcceso.inicioDelDia(sede, desde), CajaAcceso.finDelDia(sede, hasta),
						acotar(limite), Math.max(desplazamiento, 0))
				.stream()
				.map(ObligacionView::de)
				.toList();
	}

	/** Abre el borrador, opcionalmente con las prestaciones que ya se eligieron (RF-M21-002). */
	public PresentacionView crear(
			OperatingActor actor, long consultorioId, PresentacionCommands.Alta alta) {
		return conElLoteDelGanador(() -> transaccionReadCommitted.execute(
				estado -> crearEnTransaccion(actor, consultorioId, alta)));
	}

	private PresentacionView crearEnTransaccion(
			OperatingActor actor, long consultorioId, PresentacionCommands.Alta alta) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		ConsultorioSnapshot sede = acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);
		FinanciadorSnapshot financiador = acceso.exigirFinanciador(
				organizationId, alta.financiadorId());

		Instant ahora = Instant.now();
		Presentacion presentacion = presentaciones.save(new Presentacion(
				organizationId, consultorioId, alta.financiadorId(),
				alta.periodoDesde(), alta.periodoHasta(), alta.moneda(),
				ahora, actor.accountId()));

		for (Long obligacionId : alta.obligacionIds()) {
			incluir(organizationId, sede, presentacion, obligacionId, ahora);
		}
		presentacion.recalcularTotal(items.sumarPresentado(presentacion.getId()));

		auditar(actor, AuditEvents.PRESENTACION_CREADA, presentacion, null, ahora);
		log.info("Presentacion creada: presentacionId={} financiadorId={} periodo={}..{} items={}",
				presentacion.getId(), alta.financiadorId(), alta.periodoDesde(),
				alta.periodoHasta(), alta.obligacionIds().size());

		return vista(organizationId, presentacion, financiador.nombre());
	}

	/** Agrega una prestacion al borrador (RF-M21-002). */
	public PresentacionItemView agregarItem(
			OperatingActor actor, long consultorioId, long presentacionId, long obligacionId) {
		return conElLoteDelGanador(() -> transaccionReadCommitted.execute(
				estado -> agregarItemEnTransaccion(actor, consultorioId, presentacionId, obligacionId)));
	}

	private PresentacionItemView agregarItemEnTransaccion(
			OperatingActor actor, long consultorioId, long presentacionId, long obligacionId) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		ConsultorioSnapshot sede = acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);

		// ANTES de cargar la entidad: ver PresentacionRepositoryPort.bloquearParaEditar.
		presentaciones.bloquearParaEditar(organizationId, presentacionId);
		Presentacion presentacion = exigirPresentacion(organizationId, consultorioId, presentacionId);
		exigirEditable(presentacion);

		PresentacionItem item = incluir(
				organizationId, sede, presentacion, obligacionId, Instant.now());
		presentacion.recalcularTotal(items.sumarPresentado(presentacionId));

		return PresentacionItemView.de(item);
	}

	/**
	 * Quita una prestacion del borrador (RF-M21-002).
	 *
	 * <p><b>Solo en borrador.</b> Un item de un lote confirmado no se quita por ningun camino: se
	 * debita, que deja motivo, actor e instante. Cambiar lo que se reclamo despues de reclamarlo,
	 * sin que el financiador se entere, no es una correccion: es reescribir el reclamo.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public void quitarItem(
			OperatingActor actor, long consultorioId, long presentacionId, long itemId) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);

		presentaciones.bloquearParaEditar(organizationId, presentacionId);
		Presentacion presentacion = exigirPresentacion(organizationId, consultorioId, presentacionId);
		exigirEditable(presentacion);

		PresentacionItem item = items
				.findByIdEnLaPresentacion(organizationId, presentacionId, itemId)
				.orElseThrow(() -> new PresentacionItemNotAccessibleException(itemId));

		items.borrarDelBorrador(item);
		presentacion.recalcularTotal(items.sumarPresentado(presentacionId));

		log.info("Item quitado del borrador: presentacionId={} itemId={}", presentacionId, itemId);
	}

	// =================================================================================
	// Validacion y confirmacion
	// =================================================================================

	/**
	 * Detecta las prestaciones que no se pueden reclamar, antes de confirmar (RF-M21-003).
	 *
	 * <p>Es una lectura: no cambia estado y no bloquea nada. Lo que bloquea es {@link #confirmar},
	 * que la vuelve a correr del lado del servidor — el frontend nunca es autoridad.
	 */
	@Transactional(readOnly = true)
	public ValidacionDePresentacion validar(
			OperatingActor actor, long consultorioId, long presentacionId) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);

		Presentacion presentacion = exigirPresentacion(organizationId, consultorioId, presentacionId);
		List<ValidacionDePresentacion.Reparo> todos = reparosDe(organizationId, presentacion);
		List<ValidacionDePresentacion.Reparo> reparos = bloqueantes(todos);

		return new ValidacionDePresentacion(
				presentacionId,
				reparos.isEmpty() && !items.findDeLaPresentacion(presentacionId).isEmpty(),
				reparos,
				todos.stream().filter(reparo -> !reparo.hallazgo().bloquea()).toList());
	}

	private static List<ValidacionDePresentacion.Reparo> bloqueantes(
			List<ValidacionDePresentacion.Reparo> todos) {
		return todos.stream().filter(reparo -> reparo.hallazgo().bloquea()).toList();
	}

	/**
	 * Confirma el envio del lote (RF-M21-004).
	 *
	 * <p><b>Confirmar no cobra.</b> Asigna numero, congela el total y saca el lote del borrador; no
	 * toca el saldo de ninguna obligacion ni genera un movimiento de caja.
	 *
	 * <p>El numero se pide <b>despues</b> de validar, por lo mismo que 06.05 evalua la idempotencia
	 * antes del numerador: un lote que no se puede confirmar no puede consumir un numero de la
	 * serie, porque el hueco despues no se puede explicar.
	 */
	public PresentacionView confirmar(
			OperatingActor actor, long consultorioId, long presentacionId) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);

		// ANTES de abrir la transaccion de la confirmacion (FilaLockPrevia).
		asegurarNumerador(organizationId, consultorioId, presentacionId);

		return transaccionReadCommitted.execute(estado -> confirmarEnTransaccion(
				actor, consultorioId, presentacionId, organizationId));
	}

	private PresentacionView confirmarEnTransaccion(
			OperatingActor actor, long consultorioId, long presentacionId,
			long organizationId) {

		Presentacion presentacion = exigirPresentacion(organizationId, consultorioId, presentacionId);
		exigirEditable(presentacion);

		List<PresentacionItem> incluidos = items.findDeLaPresentacion(presentacionId);
		if (incluidos.isEmpty()) {
			throw new PresentacionVaciaException(presentacionId);
		}
		// Los avisos (la credencial) no frenan el envio: ver HallazgoDeValidacion#bloquea.
		List<ValidacionDePresentacion.Reparo> reparos =
				bloqueantes(reparosDe(organizationId, presentacion));
		if (!reparos.isEmpty()) {
			throw new PresentacionConHallazgosException(presentacionId, reparos.stream()
					.map(reparo -> reparo.obligacionId() + ":" + reparo.hallazgo().name())
					.toList());
		}

		presentacion.recalcularTotal(items.sumarPresentado(presentacionId));

		Instant ahora = Instant.now();
		presentacion.confirmar(
				siguienteNumero(organizationId, consultorioId, presentacion.getFinanciadorId()),
				ahora, actor.accountId());

		auditar(actor, AuditEvents.PRESENTACION_CONFIRMADA, presentacion, null, ahora);
		log.info("Presentacion confirmada: presentacionId={} numero={} total={} items={}",
				presentacionId, presentacion.getNumero(), presentacion.getTotalPresentado(),
				incluidos.size());

		return vista(organizationId, presentacion);
	}

	// =================================================================================
	// Factura, debitos y conciliacion
	// =================================================================================

	/**
	 * Registra el comprobante externo del centro (RF-M21-005).
	 *
	 * <p>El sistema no lo genera ni lo numera: lo registra. Lo unico que puede hacer es impedir que
	 * el mismo numero quede asociado a dos lotes del mismo financiador — el caso borde "factura
	 * externa duplicada"—, y eso lo garantiza el unique de V56. La consulta previa existe para
	 * poder responder un 409 legible en vez de dejar reventar la constraint, que ademas dejaria la
	 * transaccion marcada para rollback.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PresentacionView registrarFactura(
			OperatingActor actor, long consultorioId, long presentacionId,
			PresentacionCommands.Factura factura) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);

		Presentacion presentacion = exigirPresentacion(organizationId, consultorioId, presentacionId);
		exigirEnCurso(presentacion, "PRESENTADA o FACTURADA");

		if (presentaciones.existeFactura(
				organizationId, presentacion.getFinanciadorId(), factura.numero())) {
			throw new FacturaDuplicadaException(factura.numero());
		}

		Instant ahora = Instant.now();
		presentacion.registrarFactura(factura.numero(), factura.fecha(), ahora, actor.accountId());

		auditar(actor, AuditEvents.PRESENTACION_FACTURADA, presentacion, null, ahora);
		log.info("Factura registrada: presentacionId={} numero={}", presentacionId,
				factura.numero());

		return vista(organizationId, presentacion);
	}

	/**
	 * Registra el debito que el financiador informo sobre una prestacion (RF-M21-006).
	 *
	 * <p><b>No borra la prestacion ni perdona la deuda</b> (RN-M21-004). Un debito por el total
	 * deja el item {@code DEBITADO}: la obligacion queda pendiente y <b>liberada</b> para otro lote,
	 * porque ese estado pone la columna generada {@code ocupa_marca} en NULL. Un debito parcial lo
	 * deja {@code DEBITADO_PARCIAL}, que <b>sigue ocupando</b> la obligacion hasta conciliar: lo no
	 * debitado todavia se cobra en este lote (AKINE-07.04b). Que se hace con lo debitado
	 * —re-presentarlo, pasarlo al paciente— es una decision del centro que el sistema no toma.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PresentacionItemView debitar(
			OperatingActor actor, long consultorioId, long presentacionId, long itemId,
			PresentacionCommands.Debito debito) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);

		Presentacion presentacion = exigirPresentacion(organizationId, consultorioId, presentacionId);
		exigirEnCurso(presentacion, "PRESENTADA o FACTURADA");

		PresentacionItem item = items
				.findByIdEnLaPresentacion(organizationId, presentacionId, itemId)
				.orElseThrow(() -> new PresentacionItemNotAccessibleException(itemId));

		// Primero lo que se decide sin la base: estado del item y que el importe entre en lo
		// presentado. Un debito invalido no llega a mover el saldo del lote.
		item.exigirDebitable(debito.importe());

		// Despues el saldo del lote, ANTES de marcar el item, porque es la operacion que puede
		// fallar por una condicion del motor: si no alcanza, no queda un item marcado como debitado
		// por un debito que no se registro. Mismo orden que MovimientoCajaService.asentar.
		moverSaldo(
				organizationId, presentacion,
				presentaciones.registrarDebito(organizationId, presentacionId, debito.importe()),
				debito.importe());

		Instant ahora = Instant.now();
		item.debitar(debito.importe(), debito.motivo(), ahora, actor.accountId());
		// El save NO es redundante. registrarDebito es un UPDATE nativo con clearAutomatically:
		// vacia la sesion JPA y el item cargado arriba queda DESACOPLADO. Sin este save el debito
		// movia el saldo del lote y el item seguia INCLUIDO en la base —ocupando la obligacion y
		// sin motivo—, aunque la vista devuelta dijera DEBITADO. Lo destapo el escenario 38.
		items.save(item);

		auditar(actor, AuditEvents.PRESENTACION_ITEM_DEBITADO, presentacion, debito.motivo(), ahora);
		log.info("Debito registrado: presentacionId={} itemId={} importe={} obligacionId={}",
				presentacionId, itemId, debito.importe(), item.getObligacionId());

		return PresentacionItemView.de(item);
	}

	/**
	 * Cierra el lote cuando todo lo reclamado quedo explicado (RF-M21-008).
	 *
	 * <h2>Exige saldo cero, y no hay ajuste</h2>
	 *
	 * <p>No hay cierre con diferencia y no hay write-off silencioso. Conciliar con residual haria
	 * que el residual dejara de estar en ninguna parte como lo que es —plata reclamada y no
	 * cobrada— y que la cuenta corriente cuadrara por definicion. Prorratearlo entre los items
	 * inventaria un debito que el financiador nunca informo. Mismo criterio con el que 07.03 se
	 * niega a ajustar la diferencia de arqueo con un movimiento que la iguale.
	 *
	 * <h2>Y recien aca se salda la deuda</h2>
	 *
	 * <p>Cada item que sigue {@code INCLUIDO} pasa a {@code ACEPTADO} y su obligacion se descuenta
	 * por el importe exacto que se presento. Cada {@code DEBITADO_PARCIAL} pasa a
	 * {@code ACEPTADO_PARCIAL} y su obligacion se descuenta por lo presentado menos lo debitado:
	 * queda pendiente solo lo debitado, re-presentable en otro lote (AKINE-07.04b). Un
	 * {@code DEBITADO} no salda nada. <b>No se hace al recibir el pago</b> porque un pago es
	 * un importe global: repartirlo entre los items exigiria una regla de imputacion que el
	 * financiador no informo, y produciria obligaciones marcadas como pagadas que el nunca acepto.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PresentacionView conciliar(
			OperatingActor actor, long consultorioId, long presentacionId) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);

		Presentacion presentacion = exigirPresentacion(organizationId, consultorioId, presentacionId);
		exigirEnCurso(presentacion, "PRESENTADA o FACTURADA");

		if (!presentacion.estaSaldada()) {
			throw new PresentacionNoConciliaException(presentacionId, presentacion.getSaldo());
		}

		int aceptados = 0;
		for (PresentacionItem item : items.findDeLaPresentacion(presentacionId)) {
			// INCLUIDO salda lo presentado; DEBITADO_PARCIAL salda lo presentado menos lo debitado
			// y deja pendiente —y liberado para otro lote— solo lo debitado (AKINE-07.04b). El
			// resto no salda nada.
			BigDecimal aSaldar = item.conciliar();
			if (aSaldar.signum() <= 0) {
				continue;
			}
			// El mismo UPDATE condicional que 07.02 usa para imputar. Cero filas significa que la
			// deuda ya se salde por otra via —un cobro al paciente, por ejemplo—; no es un error y
			// no puede hacer fallar el cierre del lote, que ya esta explicado por completo.
			if (cobros.descontarSaldo(organizationId, item.getObligacionId(), aSaldar) > 0) {
				cobros.actualizarEstadoPorSaldo(organizationId, item.getObligacionId());
			} else {
				log.warn("La obligacion {} ya no tenia el saldo aceptado ({}) al conciliar la "
						+ "presentacion {}: se acepta el item y no se descuenta nada",
						item.getObligacionId(), aSaldar, presentacionId);
			}
			aceptados++;
		}

		Instant ahora = Instant.now();
		presentacion.conciliar(ahora, actor.accountId());

		auditar(actor, AuditEvents.PRESENTACION_CONCILIADA, presentacion, null, ahora);
		log.info("Presentacion conciliada: presentacionId={} aceptados={} debitado={} cobrado={}",
				presentacionId, aceptados, presentacion.getTotalDebitado(),
				presentacion.getTotalCobrado());

		return vista(organizationId, presentacion);
	}

	/**
	 * Descarta el borrador con motivo.
	 *
	 * <p><b>No borra y no se puede aplicar a un lote enviado.</b> Una presentacion confirmada existe
	 * del otro lado del mostrador: si el financiador la rechaza entera, eso son debitos sobre todos
	 * sus items, que es lo que efectivamente paso. Hacer desaparecer el lote borraria la unica
	 * evidencia de que se reclamo.
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PresentacionView anular(
			OperatingActor actor, long consultorioId, long presentacionId, String motivo) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);

		Presentacion presentacion = exigirPresentacion(organizationId, consultorioId, presentacionId);
		exigirEditable(presentacion);

		// Los items pasan a ANULADO y NO se borran: la marca generada los libera —la obligacion
		// vuelve a estar disponible— y la fila conserva la evidencia de que se penso presentar eso.
		items.findDeLaPresentacion(presentacionId).forEach(PresentacionItem::anular);

		Instant ahora = Instant.now();
		presentacion.anular(motivo, ahora, actor.accountId());

		auditar(actor, AuditEvents.PRESENTACION_ANULADA, presentacion, motivo, ahora);
		log.info("Presentacion anulada: presentacionId={} motivo={}", presentacionId, motivo);

		return vista(organizationId, presentacion);
	}

	// =================================================================================
	// Consultas
	// =================================================================================

	/** Las bandejas por estado. */
	@Transactional(readOnly = true)
	public List<PresentacionView> buscar(
			OperatingActor actor, long consultorioId, String estado, Long financiadorId,
			LocalDate desde, LocalDate hasta, int limite, int desplazamiento) {
		return buscar(actor, consultorioId, estado, financiadorId, desde, hasta, null, limite,
				desplazamiento);
	}

	/**
	 * La bandeja con busqueda por texto (DP-45). {@code q} busca "contiene" en el numero de
	 * factura y, si es un numero, el lote con ese numero. No busca en el nombre del financiador:
	 * es de {@code contracting} y el {@code spi} lo resuelve por id, no por texto; para eso esta el
	 * filtro {@code financiadorId}. Mismo orden y mismo corte que siempre.
	 */
	@Transactional(readOnly = true)
	public List<PresentacionView> buscar(
			OperatingActor actor, long consultorioId, String estado, Long financiadorId,
			LocalDate desde, LocalDate hasta, String q, int limite, int desplazamiento) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);

		// Un nombre por financiador y no uno por fila: una bandeja de cien lotes de tres obras
		// sociales haria cien consultas al spi para responder tres veces lo mismo.
		Map<Long, String> nombres = new LinkedHashMap<>();
		return presentaciones.buscar(
						organizationId, consultorioId, estado, financiadorId, desde, hasta,
						TextoDeBusqueda.patronLike(q), numeroDelLote(q),
						acotar(limite), Math.max(desplazamiento, 0))
				.stream()
				.map(presentacion -> PresentacionView.de(presentacion, nombres.computeIfAbsent(
						presentacion.getFinanciadorId(),
						id -> acceso.nombreDe(organizationId, id))))
				.toList();
	}

	/** El texto como numero de lote, si lo es; si no, {@code null}, que no coincide con nada. */
	private static Integer numeroDelLote(String q) {
		String texto = q == null ? "" : q.strip();
		if (texto.isEmpty() || texto.length() > 9 || !texto.chars().allMatch(Character::isDigit)) {
			return null;
		}
		return Integer.valueOf(texto);
	}

	/** El detalle con sus items. */
	@Transactional(readOnly = true)
	public PresentacionView detalle(
			OperatingActor actor, long consultorioId, long presentacionId) {

		long organizationId = PresentacionAcceso.exigirContexto(actor);
		acceso.exigirSedeDelTenant(organizationId, consultorioId);
		acceso.exigirOperar(actor, organizationId, consultorioId);

		return vista(
				organizationId,
				exigirPresentacion(organizationId, consultorioId, presentacionId));
	}

	// =================================================================================
	// Interno
	// =================================================================================

	/**
	 * Corre el alta o el agregado, y si perdio la carrera de RN-M21-003 responde con el lote que
	 * gano.
	 *
	 * <p>El lote se lee <b>despues</b> de que la transaccion del perdedor revirtio y devolvio su
	 * conexion: esa sesion JPA ya fallo un insert y no se vuelve a tocar. Hasta el 11/10/2026 la
	 * lectura era un {@code REQUIRES_NEW} desde adentro del {@code catch}, con la conexion del
	 * perdedor todavia tomada: dos conexiones por pedido, el traba del pool de docs/carga.md §8.
	 */
	private <T> T conElLoteDelGanador(Supplier<T> enTransaccion) {
		try {
			return enTransaccion.get();
		} catch (ObligacionOcupadaEnCarrera carrera) {
			long lote = ocupacion.loteQueLaOcupa(carrera.organizationId(), carrera.obligacionId())
					.orElseThrow(carrera::choque);
			throw new ObligacionYaPresentadaException(carrera.obligacionId(), lote);
		}
	}

	/**
	 * Incluye una obligacion en el lote, con todas sus comprobaciones.
	 *
	 * <p>La consulta previa a {@code findVivoDeLaObligacion} no es el mecanismo de RN-M21-003 —ese
	 * es el unique sobre la columna generada— sino lo que permite responder un 409 <b>con el lote
	 * que la tiene</b>, para que el administrativo pueda ir a mirarlo.
	 */
	private PresentacionItem incluir(
			long organizationId, ConsultorioSnapshot sede, Presentacion presentacion,
			long obligacionId, Instant ahora) {

		Obligacion obligacion = obligaciones
				.findByIdInScope(organizationId, presentacion.getConsultorioId(), obligacionId)
				.orElseThrow(() -> new ObligacionNotAccessibleException(obligacionId));

		Optional<HallazgoDeValidacion> hallazgo = revisar(presentacion, obligacion);
		if (hallazgo.isPresent()) {
			throw new ObligacionNoPresentableException(obligacionId, hallazgo.get().name());
		}

		items.findVivoDeLaObligacion(organizationId, obligacionId).ifPresent(vivo -> {
			throw new ObligacionYaPresentadaException(obligacionId, vivo.getPresentacionId());
		});

		try {
			return items.save(new PresentacionItem(
					organizationId,
					presentacion.getId(),
					obligacion,
					obligacion.getDevengadaEn().atZone(ZoneId.of(sede.timezone())).toLocalDate(),
					ahora));
		} catch (DataIntegrityViolationException choque) {
			// La ventana de la consulta de arriba: otro administrativo agrego la misma obligacion a
			// otro lote entre que leimos y escribimos, y el unique sobre la columna generada lo
			// freno. Sin esta traduccion el perdedor recibia el 409 generico `conflict`, sin el lote
			// que la tiene, en vez del `obligacion-ya-presentada` que el contrato promete. Lo
			// encontro PresentacionItemConcurrenteIT. El lote se lee cuando esta transaccion ya
			// revirtio (conElLoteDelGanador): esta sesion JPA ya fallo un insert.
			if (!indiceContiene(choque, "uk_presentacion_item_ocupa")) {
				throw choque;
			}
			throw new ObligacionOcupadaEnCarrera(organizationId, obligacionId, choque);
		}
	}

	/** Mismo criterio que {@code FinanciadorService}: el nombre del indice es la unica senal. */
	private static boolean indiceContiene(DataIntegrityViolationException choque, String indice) {
		String mensaje = choque.getMostSpecificCause().getMessage();
		return mensaje != null && mensaje.toLowerCase(Locale.ROOT).contains(indice);
	}

	/**
	 * Las comprobaciones de RF-M21-003 que el sistema puede hacer hoy.
	 *
	 * <p>Los tres requisitos documentales del convenio —orden, autorizacion y credencial— no se
	 * miran aca sino en {@link #revisarRespaldo}, que solo corre al validar y confirmar: esto lo usa
	 * tambien {@code incluir}, y una orden faltante no debe impedir que el item entre al lote, donde
	 * la validacion lo puede mostrar (AKINE-07.04b).
	 */
	private Optional<HallazgoDeValidacion> revisar(
			Presentacion presentacion, Obligacion obligacion) {

		if (obligacion.getAnuladaEn() != null) {
			return Optional.of(HallazgoDeValidacion.OBLIGACION_ANULADA);
		}
		if (obligacion.getSaldo().signum() <= 0) {
			return Optional.of(HallazgoDeValidacion.SIN_SALDO);
		}
		if (obligacion.getResponsable() != Responsable.FINANCIADOR) {
			return Optional.of(HallazgoDeValidacion.DEUDA_DEL_PACIENTE);
		}
		if (!presentacion.getFinanciadorId().equals(obligacion.getFinanciadorId())) {
			return Optional.of(HallazgoDeValidacion.FINANCIADOR_DISTINTO);
		}
		if (!presentacion.getConsultorioId().equals(obligacion.getConsultorioId())) {
			return Optional.of(HallazgoDeValidacion.SEDE_DISTINTA);
		}
		if (!presentacion.getMoneda().equals(obligacion.getMoneda())) {
			return Optional.of(HallazgoDeValidacion.MONEDA_DISTINTA);
		}
		return Optional.empty();
	}

	/**
	 * Las mismas comprobaciones, sobre todos los items vivos del lote, mas el periodo y los
	 * requisitos documentales del convenio congelado. Devuelve reparos y avisos juntos: quien llama
	 * los separa por {@link HallazgoDeValidacion#bloquea()}.
	 */
	private List<ValidacionDePresentacion.Reparo> reparosDe(
			long organizationId, Presentacion presentacion) {

		List<ValidacionDePresentacion.Reparo> reparos = new ArrayList<>();

		for (PresentacionItem item : items.findDeLaPresentacion(presentacion.getId())) {
			if (!item.getEstado().ocupa()) {
				continue;
			}
			Optional<Obligacion> obligacion = obligaciones.findByIdInScope(
					organizationId, presentacion.getConsultorioId(), item.getObligacionId());
			obligacion
					.flatMap(o -> revisar(presentacion, o))
					.ifPresent(hallazgo -> reparos.add(new ValidacionDePresentacion.Reparo(
							item.getId(), item.getObligacionId(), hallazgo)));
			obligacion.ifPresent(o -> revisarRespaldo(organizationId, item, o)
					.forEach(hallazgo -> reparos.add(new ValidacionDePresentacion.Reparo(
							item.getId(), item.getObligacionId(), hallazgo))));

			// El periodo se evalua contra la fecha CONGELADA del item, no contra la obligacion:
			// es la que el lote va a imprimir, y evaluarla en la zona de la sede es lo que impide
			// que una prestacion de las 21:30 del 31 caiga en el mes siguiente.
			LocalDate prestacion = item.getSnapshotFechaPrestacion();
			if (prestacion.isBefore(presentacion.getPeriodoDesde())
					|| prestacion.isAfter(presentacion.getPeriodoHasta())) {
				reparos.add(new ValidacionDePresentacion.Reparo(
						item.getId(), item.getObligacionId(),
						HallazgoDeValidacion.FUERA_DEL_PERIODO));
			}
		}
		return reparos;
	}

	/**
	 * Orden, autorizacion y credencial (RF-M21-003, AKINE-07.04b).
	 *
	 * <p>Mira <b>lo que el convenio exigia el dia de la prestacion</b>, congelado en la obligacion
	 * por F-4, y no el convenio de hoy: un lote de agosto se juzga con las reglas de agosto. Un
	 * {@code null} —obligacion anterior a F-4, o sin convenio— no exige nada (RN-M17-005/006).
	 *
	 * <p>A {@code person} se le pregunta solo si algo se exige: un lote de doscientas prestaciones
	 * de un convenio que no pide papeles no hace doscientas lecturas para nada.
	 */
	private List<HallazgoDeValidacion> revisarRespaldo(
			long organizationId, PresentacionItem item, Obligacion obligacion) {

		Optional<SnapshotDeConvenio> congelado = obligacion.getSnapshotDeConvenio();
		if (congelado.isEmpty()) {
			return List.of();
		}
		SnapshotDeConvenio convenio = congelado.get();
		if (!convenio.requeriaOrden() && !convenio.requeriaAutorizacion()
				&& !convenio.requeriaCredencial()) {
			return List.of();
		}

		RespaldoDocumental respaldo = respaldos.respaldo(
				organizationId, obligacion.getPersonaId(), convenio.coberturaId(),
				convenio.practicaId(), item.getSnapshotFechaPrestacion());

		List<HallazgoDeValidacion> hallazgos = new ArrayList<>(3);
		if (convenio.requeriaOrden() && !respaldo.orden()) {
			hallazgos.add(HallazgoDeValidacion.SIN_ORDEN);
		}
		if (convenio.requeriaAutorizacion() && !respaldo.autorizacion()) {
			hallazgos.add(HallazgoDeValidacion.SIN_AUTORIZACION);
		}
		if (convenio.requeriaCredencial()
				&& (convenio.credencialVencida() || !respaldo.numeroAfiliado())) {
			hallazgos.add(HallazgoDeValidacion.CREDENCIAL_FALTANTE);
		}
		return hallazgos;
	}

	/**
	 * Traduce cero filas del {@code UPDATE} condicional.
	 *
	 * <p>Releer es seguro: un {@code UPDATE} de cero filas <b>no marca la transaccion para
	 * rollback</b>, a diferencia de un {@code flush} fallido por constraint, que si la marca y hace
	 * que cualquier consulta posterior termine en {@code UnexpectedRollbackException}. Este
	 * repositorio ya lo pago cuatro veces.
	 */
	private void moverSaldo(
			long organizationId, Presentacion presentacion, int filas, BigDecimal importe) {

		if (filas > 0) {
			return;
		}
		Presentacion actual = presentaciones
				.findByIdInScope(organizationId, presentacion.getConsultorioId(),
						presentacion.getId())
				.orElseThrow(() -> new PresentacionNotAccessibleException(presentacion.getId()));

		if (!actual.getEstado().estaEnCurso()) {
			throw new PresentacionEstadoInvalidoException(
					actual.getId(), actual.getEstado().name(), "PRESENTADA o FACTURADA");
		}
		throw new PresentacionSaldoInsuficienteException(
				actual.getId(), importe, actual.getSaldo());
	}

	/**
	 * La fila de {@code presentacion_numerador} del financiador de esa presentacion, en su propia
	 * transaccion y fuera de la de la confirmacion. Si la presentacion no existe, adentro se responde
	 * el 404 de siempre.
	 */
	private void asegurarNumerador(long organizationId, long consultorioId, long presentacionId) {
		presentaciones.findByIdInScope(organizationId, consultorioId, presentacionId)
				.ifPresent(presentacion -> numeradorIniciador.asegurar(
						organizationId, consultorioId, presentacion.getFinanciadorId()));
	}

	private int siguienteNumero(long organizationId, long consultorioId, long financiadorId) {
		numerador.incrementar(organizationId, consultorioId, financiadorId);
		return numerador.leerUltimo(organizationId, consultorioId, financiadorId);
	}

	private Presentacion exigirPresentacion(
			long organizationId, long consultorioId, long presentacionId) {

		return presentaciones.findByIdInScope(organizationId, consultorioId, presentacionId)
				.orElseThrow(() -> new PresentacionNotAccessibleException(presentacionId));
	}

	private static void exigirEditable(Presentacion presentacion) {
		if (!presentacion.getEstado().esEditable()) {
			throw new PresentacionNoEditableException(
					presentacion.getId(), presentacion.getEstado().name());
		}
	}

	private static void exigirEnCurso(Presentacion presentacion, String esperado) {
		if (!presentacion.getEstado().estaEnCurso()) {
			throw new PresentacionEstadoInvalidoException(
					presentacion.getId(), presentacion.getEstado().name(), esperado);
		}
	}

	private PresentacionView vista(long organizationId, Presentacion presentacion) {
		return vista(organizationId, presentacion,
				acceso.nombreDe(organizationId, presentacion.getFinanciadorId()));
	}

	private PresentacionView vista(
			long organizationId, Presentacion presentacion, String financiadorNombre) {

		return PresentacionView.de(presentacion, financiadorNombre,
				items.findDeLaPresentacion(presentacion.getId()).stream()
						.map(PresentacionItemView::de)
						.toList());
	}

	private static int acotar(int limite) {
		return Math.min(Math.max(limite, 1), LIMITE_MAXIMO);
	}

	/** La auditoria se escribe DENTRO de la transaccion del negocio, nunca post-commit. */
	private void auditar(
			OperatingActor actor, String eventType, Presentacion presentacion,
			String motivo, Instant ahora) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("financiadorId", String.valueOf(presentacion.getFinanciadorId()));
		detalles.put("estado", presentacion.getEstado().name());
		detalles.put("totalPresentado", presentacion.getTotalPresentado().toPlainString());
		detalles.put("saldo", presentacion.getSaldo().toPlainString());
		if (presentacion.getNumero() != null) {
			detalles.put("numero", String.valueOf(presentacion.getNumero()));
		}

		auditTrail.record(new AuditEntry(
				actor.contextOrganizationId(),
				actor.consultorioId(),
				actor.accountId(),
				eventType,
				AuditEvents.ENTITY_PRESENTACION,
				presentacion.getId(),
				null,
				null,
				detalles,
				motivo,
				AuditEvents.correlationId(),
				ahora));
	}
}
