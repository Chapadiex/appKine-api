package com.akine.activity.application;

import com.akine.activity.domain.EstadoPase;
import com.akine.activity.domain.MovimientoPase;
import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.ProductoServicio;
import com.akine.activity.domain.TipoProducto;
import com.akine.activity.domain.exception.AbonoPeriodoSuperpuestoException;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.PaseNoAjustableException;
import com.akine.activity.domain.exception.PaseNotAccessibleException;
import com.akine.activity.domain.exception.PersonaDadaDeBajaException;
import com.akine.activity.domain.exception.PersonaNoAccesibleException;
import com.akine.activity.domain.exception.ProductoNoVendibleException;
import com.akine.activity.domain.exception.ProductoNotAccessibleException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ProductoServicioRepositoryPort;
import com.akine.billing.spi.DevengoDeVentas;
import com.akine.billing.spi.DevengoDeVentas.DeudaDeVenta;
import com.akine.billing.spi.DevengoDeVentas.VentaDePase;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.person.spi.PacienteDirectory;
import com.akine.person.spi.PacienteSnapshot;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import com.akine.platform.spi.listado.OrdenDeListado;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Venta de un pase (AKINE-08.06, RF-M29-002 y RF-M18-009).
 *
 * <h2>Vender no es cobrar, y cobrar no es caja</h2>
 *
 * <p>La venta produce, en <b>una</b> transaccion: el pase con su snapshot, el movimiento
 * {@code COMPRA} que explica su saldo (RN-M29-003) y <b>exactamente una obligacion</b>, por
 * {@code billing.spi.DevengoDeVentas}. No escribe cobro ni caja (DP-06): quien paga usa
 * {@code POST /cobros} contra esa obligacion. Si cualquiera de las tres escrituras falla, no queda
 * ninguna (CA-M29-002-04): un pase con creditos y sin deuda seria credito regalado.
 *
 * <h2>Idempotencia, antes de todo</h2>
 *
 * <p>Con {@code Idempotency-Key}, el reintento se resuelve <b>antes</b> de validar el producto y
 * sobre todo antes de devengar: misma huella devuelve la venta existente sin escribir; otra huella
 * es 409. Dos pedidos simultaneos con la misma clave pasan los dos esa primera mirada si ninguno
 * commiteo todavia, y lo que los resuelve depende del tipo de producto (U-48, 17/10/2026):
 *
 * <ul>
 *   <li><b>Abono:</b> el producto se toma con {@code FOR UPDATE} —es lo que serializa los periodos
 *       de la misma persona—, y el segundo vuelve a mirar la clave al despertar (AKINE-09.03).</li>
 *   <li><b>Pack:</b> sin lock del producto, para que las ventas de la sede no vayan en fila
 *       (docs/carga.md §12). El segundo choca con {@code uk_pase_idempotency} al insertar; despues
 *       del rollback se relee la clave en una transaccion nueva en {@code READ_COMMITTED}, como el
 *       cobro (#131): misma huella devuelve la venta del primero, otra huella es 409.</li>
 * </ul>
 *
 * <p>{@code uk_obligacion_venta} es el cinturon del lado de {@code billing}.
 *
 * <p>{@code READ_COMMITTED}: el perdedor de una carrera sobre la clave tiene que poder ver la fila
 * del ganador, y bajo {@code REPEATABLE READ} no la ve.
 */
@Service
public class VentaDePaseService {

	private static final Logger log = LoggerFactory.getLogger(VentaDePaseService.class);

	private static final String ENTIDAD = "PASE_SERVICIO";

	private final PaseServicioRepositoryPort pases;
	private final ProductoServicioRepositoryPort productos;
	private final MovimientoPaseRepositoryPort movimientos;
	private final DevengoDeVentas devengo;
	private final PacienteDirectory personas;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final LecturaDePases lectura;
	private final TransactionTemplate transaccionDeVenta;
	private final TransactionTemplate relecturaDelGanador;
	private final Clock clock;

	@Autowired
	public VentaDePaseService(
			PaseServicioRepositoryPort pases,
			ProductoServicioRepositoryPort productos,
			MovimientoPaseRepositoryPort movimientos,
			DevengoDeVentas devengo,
			PacienteDirectory personas,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			LecturaDePases lectura,
			PlatformTransactionManager transactionManager) {

		this(pases, productos, movimientos, devengo, personas, consultorios, permissionGuard,
				auditTrail, lectura, transactionManager, Clock.systemUTC());
	}

	VentaDePaseService(
			PaseServicioRepositoryPort pases,
			ProductoServicioRepositoryPort productos,
			MovimientoPaseRepositoryPort movimientos,
			DevengoDeVentas devengo,
			PacienteDirectory personas,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			LecturaDePases lectura,
			PlatformTransactionManager transactionManager,
			Clock clock) {

		this.pases = pases;
		this.productos = productos;
		this.movimientos = movimientos;
		this.devengo = devengo;
		this.personas = personas;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.lectura = lectura;
		this.transaccionDeVenta = new TransactionTemplate(transactionManager);
		this.transaccionDeVenta.setIsolationLevel(Isolation.READ_COMMITTED.value());
		this.relecturaDelGanador = new TransactionTemplate(transactionManager);
		this.relecturaDelGanador.setIsolationLevel(Isolation.READ_COMMITTED.value());
		this.relecturaDelGanador.setReadOnly(true);
		this.clock = clock;
	}

	/**
	 * @throws ProductoNotAccessibleException  producto inexistente o de otra sede (404)
	 * @throws ProductoNoVendibleException     dado de baja, fuera de su ventana comercial o, en un
	 *                                         abono, con un periodo incoherente (409)
	 * @throws AbonoPeriodoSuperpuestoException la persona ya tiene ese abono en un periodo que se
	 *                                         superpone (409)
	 * @throws PersonaNoAccesibleException     persona fuera del padron del tenant (404)
	 * @throws PersonaDadaDeBajaException      ficha de baja (409)
	 * @throws IdempotencyKeyConflictException misma clave, otro pedido (409)
	 */
	public ResultadoDeVenta vender(OperatingActor actor, long consultorioId, VenderPaseCommand command) {
		long organizationId = ProductoService.exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.PASE_MANAGE);
		String huella = command.idempotencyKey() == null ? null : command.huella(consultorioId);

		try {
			return transaccionDeVenta.execute(estado ->
					venderEnTransaccion(actor, organizationId, sede, command, huella));
		} catch (DataIntegrityViolationException choque) {
			// Un pack no toma el producto (U-48): dos ventas con la misma clave llegan las dos al
			// INSERT, y la segunda choca con uk_pase_idempotency cuando la primera commitea.
			return ventaQueGanoLaCarrera(organizationId, sede, command, huella, actor)
					.orElseThrow(() -> choque);
		}
	}

	/**
	 * La venta del pedido con la misma clave que commiteo primero, leida <b>despues</b> del rollback y
	 * en una transaccion nueva en {@code READ_COMMITTED}: la foto de la transaccion que choco podria
	 * no verla. Vacio si no hay clave o si nadie la uso: el choque era otro y sigue su camino.
	 */
	private Optional<ResultadoDeVenta> ventaQueGanoLaCarrera(
			long organizationId, ConsultorioSnapshot sede, VenderPaseCommand command, String huella,
			OperatingActor actor) {

		if (command.idempotencyKey() == null) {
			return Optional.empty();
		}
		return Optional.ofNullable(relecturaDelGanador.execute(estado ->
				reintento(organizationId, command, huella, ZonaDeSede.hoy(sede, clock), actor).orElse(null)));
	}

	private ResultadoDeVenta venderEnTransaccion(
			OperatingActor actor, long organizationId, ConsultorioSnapshot sede, VenderPaseCommand command,
			String huella) {

		long consultorioId = sede.id();
		LocalDate hoy = ZonaDeSede.hoy(sede, clock);
		Optional<ResultadoDeVenta> reintento = reintento(organizationId, command, huella, hoy, actor);
		if (reintento.isPresent()) {
			return reintento.get();
		}

		// U-48 (17/10/2026): el producto se BLOQUEA solo si es un abono. El tipo es inmutable
		// (updatable = false) y se pregunta sin cargar la entidad, para que el lock del abono lea la
		// fila actual y no una copia que la sesion ya tuviera.
		TipoProducto tipo = productos.tipoDe(organizationId, consultorioId, command.productoId())
				.orElseThrow(() -> new ProductoNotAccessibleException(command.productoId()));
		ProductoServicio producto;
		if (tipo == TipoProducto.ABONO) {
			// En un abono el lock serializa las ventas del mismo producto, que es lo que hace cumplir
			// "un periodo, una obligacion" (AKINE-08.08): dos periodos superpuestos vendidos a la vez
			// no comparten un valor de columna que un unique pueda ver. Orden: producto_servicio ->
			// pase_servicio.
			producto = productos.lockByIdInScope(organizationId, consultorioId, command.productoId())
					.orElseThrow(() -> new ProductoNotAccessibleException(command.productoId()));
			// SEGUNDA MIRADA A LA CLAVE, YA CON EL PRODUCTO BLOQUEADO (AKINE-09.03): el segundo de
			// dos pedidos con la misma clave, al despertar, lee en READ_COMMITTED la venta del primero.
			reintento = reintento(organizationId, command, huella, hoy, actor);
			if (reintento.isPresent()) {
				return reintento.get();
			}
		} else {
			// En un pack, sin lock: lectura en READ_COMMITTED, que ve una baja ya commiteada. La que
			// commitea despues de esta lectura se cuela: es una venta del ultimo instante, que el
			// mostrador puede anular. La carrera de la clave la resuelve uk_pase_idempotency y
			// vender() relee al ganador.
			producto = productos.findByIdInScope(organizationId, consultorioId, command.productoId())
					.orElseThrow(() -> new ProductoNotAccessibleException(command.productoId()));
		}
		boolean abono = producto.getTipo() == TipoProducto.ABONO;
		if (!abono && (command.periodoDesde() != null || command.periodoHasta() != null)) {
			throw new IllegalArgumentException(
					"Un pack vale desde la compra: el periodo es solo para abonos");
		}
		if (!abono && command.renovacionAutomatica()) {
			throw new IllegalArgumentException(
					"Un pack no se renueva: la renovacion automatica es solo para abonos");
		}
		producto.impedimentoParaVenderEl(hoy).ifPresent(motivo -> {
			throw new ProductoNoVendibleException(command.productoId(), motivo);
		});
		exigirPersonaDelPadron(organizationId, command.personaId());

		Instant ahora = clock.instant();
		PaseServicio nuevo = abono
				? venderAbono(organizationId, producto, command, hoy, huella, actor, ahora)
				: PaseServicio.vender(producto, command.personaId(), hoy, command.idempotencyKey(), huella,
						actor.accountId(), ahora);
		if (command.renovacionAutomatica()) {
			nuevo.pedirRenovacionAutomatica(actor.accountId());
		}
		PaseServicio pase = pases.saveAndFlush(nuevo);
		movimientos.registrar(MovimientoPase.compra(pase, actor.accountId(), ahora));
		DeudaDeVenta deuda = devengar(pase, actor);

		auditar(actor, pase, sede, deuda, ahora);
		log.info("Pase vendido: paseId={} productoId={} personaId={} creditos={} obligacionId={}",
				pase.getId(), producto.getId(), command.personaId(), pase.getCreditosIniciales(),
				deuda.obligacionId());
		return new ResultadoDeVenta(PaseView.de(pase, hoy, lectura.uso(organizationId, pase, 0, false)), deuda(deuda), true);
	}

	/** El pase con su ledger completo (RF-M29-009). */
	@Transactional(readOnly = true)
	public PaseDetalleView find(OperatingActor actor, long consultorioId, long paseId) {
		long organizationId = ProductoService.exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.PASE_READ);
		PaseServicio pase = pases.findByIdInScope(organizationId, consultorioId, paseId)
				.orElseThrow(() -> new PaseNotAccessibleException(paseId));
		return new PaseDetalleView(
				PaseView.de(pase, ZonaDeSede.hoy(sede, clock),
						lectura.uso(organizationId, pase)),
				movimientos.historial(organizationId, paseId).stream()
						.map(MovimientoPaseView::de)
						.toList());
	}

	/**
	 * Una pagina del ledger del pase, del movimiento mas viejo al mas nuevo (AKINE-08.08,
	 * RF-M29-009, RNF-M26-010). Para un pase con historia larga reemplaza al ledger entero de
	 * {@link #find}, que sigue igual por compatibilidad.
	 *
	 * @param pagina base cero, ya normalizada por la capa HTTP
	 * @param tamano ya acotado por la capa HTTP
	 */
	@Transactional(readOnly = true)
	public Page<MovimientoPaseView> movimientos(
			OperatingActor actor, long consultorioId, long paseId, int pagina, int tamano) {
		return movimientos(actor, consultorioId, paseId, Optional.empty(), pagina, tamano);
	}

	/** DP-45: campo del contrato → propiedad del movimiento. */
	public static final Map<String, String> ORDEN_DE_MOVIMIENTOS = Map.of(
			"ocurridoEn", "ocurridoEn",
			"tipo", "tipo",
			"cantidad", "cantidad");

	/**
	 * La misma pagina con el orden que pide el cliente (DP-45). Sin orden, el de siempre: del mas
	 * viejo al mas nuevo, que es en el que se lee {@code saldoResultante}.
	 */
	@Transactional(readOnly = true)
	public Page<MovimientoPaseView> movimientos(
			OperatingActor actor, long consultorioId, long paseId, Optional<OrdenDeListado> orden,
			int pagina, int tamano) {

		long organizationId = ProductoService.exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.PASE_READ);
		pases.findByIdInScope(organizationId, consultorioId, paseId)
				.orElseThrow(() -> new PaseNotAccessibleException(paseId));
		Sort sort = orden
				.map(pedido -> pedido.aSort(ORDEN_DE_MOVIMIENTOS.get(pedido.campo()), "id"))
				.orElse(Sort.by(Sort.Direction.ASC, "ocurridoEn").and(Sort.by(Sort.Direction.ASC, "id")));
		return movimientos.historial(organizationId, paseId, PageRequest.of(pagina, tamano, sort))
				.map(MovimientoPaseView::de);
	}

	/**
	 * Prende o apaga la renovacion automatica de un abono (AKINE-08.08). Prenderla es <b>autorizar
	 * que el job venda los periodos siguientes</b> a nombre de quien la prende, con su obligacion
	 * cada uno: por eso pide {@code pase:manage}, el permiso de vender. Apagarla no anula un periodo
	 * que ya se renovo (para eso, {@code anularPase} sobre el periodo nuevo).
	 *
	 * <p>Idempotente por estado: pedir lo que ya esta no escribe ni compara version. Lo demas, con el
	 * pase bloqueado y la version que el cliente leyo, como el ajuste.
	 *
	 * @throws PaseNotAccessibleException el pase no existe en la sede (404)
	 * @throws IllegalArgumentException   es un pack: no tiene periodo que renovar (400)
	 * @throws PaseNoAjustableException   anulado ({@code ESTADO}) o ya renovado ({@code YA_RENOVADO}) (409)
	 * @throws OptimisticLockingFailureException la version no es la leida (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PaseView configurarRenovacion(
			OperatingActor actor, long consultorioId, long paseId, boolean activa, long version) {

		long organizationId = ProductoService.exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.PASE_MANAGE);
		LocalDate hoy = ZonaDeSede.hoy(sede, clock);
		PaseServicio pase = pases.lockByIdInScope(organizationId, consultorioId, paseId)
				.orElseThrow(() -> new PaseNotAccessibleException(paseId));
		if (pase.getTipo() != TipoProducto.ABONO) {
			throw new IllegalArgumentException("Un pack no se renueva: la renovacion automatica es solo para abonos");
		}
		PaseView actual = PaseView.de(pase, hoy, lectura.uso(organizationId, pase));
		if (pase.isRenovacionAutomatica() == activa && pase.getRenovacionOmitidaMotivo() == null) {
			return actual;
		}
		if (pase.getVersion() != version) {
			throw new OptimisticLockingFailureException(
					"El pase " + paseId + " cambio desde que se leyo (version " + version + ")");
		}
		if (pase.getEstado() == EstadoPase.ANULADO) {
			throw new PaseNoAjustableException(paseId, PaseNoAjustableException.Causa.ESTADO, "esta anulado");
		}
		if (activa) {
			Optional<Long> siguiente = pases.renovacionDe(organizationId, paseId);
			if (siguiente.isPresent()) {
				throw new PaseNoAjustableException(paseId, PaseNoAjustableException.Causa.YA_RENOVADO,
						"ya se renovo: el periodo siguiente es el pase " + siguiente.get()
						+ ", y la renovacion se configura sobre ese");
			}
		}
		if (pases.configurarRenovacion(organizationId, paseId, activa, actor.accountId(), version) != 1) {
			throw new OptimisticLockingFailureException(
					"El pase " + paseId + " cambio desde que se leyo (version " + version + ")");
		}
		Instant ahora = clock.instant();
		auditTrail.record(new AuditEntry(
				organizationId, sede.id(), actor.accountId(), "ABONO_RENOVACION_CONFIGURADA", ENTIDAD, paseId,
				pase.isRenovacionAutomatica() ? "RENOVACION_ACTIVA" : "RENOVACION_INACTIVA",
				activa ? "RENOVACION_ACTIVA" : "RENOVACION_INACTIVA",
				Map.of("vigenciaHasta", pase.getVigenciaHasta().toString()), null, null, ahora));
		log.info("Renovacion de abono configurada: paseId={} activa={}", paseId, activa);
		return actual.conRenovacion(activa, version + 1,
				activa ? lectura.nombreDe(actor.accountId()) : actual.renovacionAutorizadaPor());
	}

	/**
	 * Los pases que la persona compro en esta sede, el que vence mas tarde primero.
	 *
	 * <p>La persona se resuelve por el padron <b>del tenant</b>: una de otro tenant es 404. Sin eso
	 * la ruta seria un enumerador cross-tenant con datos economicos adentro.
	 */
	@Transactional(readOnly = true)
	public List<PaseView> deLaPersona(OperatingActor actor, long consultorioId, long personaId) {
		long organizationId = ProductoService.exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.PASE_READ);
		personas.find(organizationId, personaId)
				.orElseThrow(() -> new PersonaNoAccesibleException(personaId));
		LocalDate hoy = ZonaDeSede.hoy(sede, clock);
		List<PaseServicio> deLaSede = pases.findDePersona(organizationId, personaId).stream()
				.filter(pase -> pase.getConsultorioId() == consultorioId)
				.toList();
		Map<Long, PaseView.Uso> usos = lectura.usos(organizationId, deLaSede);
		return deLaSede.stream()
				.map(pase -> PaseView.de(pase, hoy, usos.get(pase.getId())))
				.toList();
	}

	// =================================================================================

	/**
	 * La venta que ya hizo un pedido con la misma clave: misma huella devuelve esa venta sin
	 * escribir; otra huella es 409. Vacio si no hay clave o si nadie la uso todavia.
	 */
	private Optional<ResultadoDeVenta> reintento(
			long organizationId, VenderPaseCommand command, String huella, LocalDate hoy, OperatingActor actor) {

		if (command.idempotencyKey() == null) {
			return Optional.empty();
		}
		return pases.findByIdempotencyKey(organizationId, command.idempotencyKey()).map(existente -> {
			if (!huella.equals(existente.getRequestHash())) {
				throw new IdempotencyKeyConflictException(command.idempotencyKey());
			}
			// El devengo es idempotente por pase: devuelve la deuda que ya existe sin escribir.
			DeudaDeVenta deuda = devengar(existente, actor);
			return new ResultadoDeVenta(
					PaseView.de(existente, hoy, lectura.uso(organizationId, existente)),
					deuda(deuda), false);
		});
	}

	/**
	 * El periodo de un abono (RF-M29-007 paso 5): desde obligatorio, hasta por defecto el que da la
	 * duracion del producto. Coherencia temporal y <b>no superposicion</b> con otro periodo vivo del
	 * mismo producto para la misma persona, con el producto ya bloqueado.
	 */
	private PaseServicio venderAbono(
			long organizationId, ProductoServicio producto, VenderPaseCommand command, LocalDate hoy,
			String huella, OperatingActor actor, Instant ahora) {

		if (command.periodoDesde() == null) {
			throw new IllegalArgumentException("Un abono se vende con su periodo: falta periodoDesde");
		}
		LocalDate desde = command.periodoDesde();
		LocalDate hasta = command.periodoHasta() != null
				? command.periodoHasta()
				: desde.plusDays(producto.getVigenciaDias() - 1L);
		PaseServicio.impedimentoDelPeriodo(producto, desde, hasta, hoy).ifPresent(motivo -> {
			throw new ProductoNoVendibleException(producto.getId(), motivo);
		});
		if (pases.existeAbonoSuperpuesto(
				organizationId, command.personaId(), producto.getId(), desde, hasta)) {
			throw new AbonoPeriodoSuperpuestoException(producto.getId(), command.personaId(), desde, hasta);
		}
		return PaseServicio.venderAbono(producto, command.personaId(), desde, hasta,
				command.idempotencyKey(), huella, actor.accountId(), ahora);
	}

	private DeudaDeVenta devengar(PaseServicio pase, OperatingActor actor) {
		VentaDePase venta = venta(pase, actor);
		return pase.getTipo() == TipoProducto.ABONO
				? devengo.devengarPorVentaDeAbono(venta)
				: devengo.devengarPorVentaDePase(venta);
	}

	private static VentaDePase venta(PaseServicio pase, OperatingActor actor) {
		return new VentaDePase(
				pase.getOrganizationId(), pase.getConsultorioId(), pase.getId(), pase.getPersonaId(),
				pase.getSnapshotOfertaId(), pase.getSnapshotNombre(), pase.getSnapshotPrecio(),
				pase.getSnapshotMoneda(), actor.accountId(), pase.getVendidoEn());
	}

	private static ResultadoDeVenta.Deuda deuda(DeudaDeVenta d) {
		return new ResultadoDeVenta.Deuda(d.obligacionId(), d.importe(), d.saldo(), d.moneda(), d.estado());
	}

	/** <b>Persona, no paciente</b>: comprar un pack no es entrar al circuito clinico (RF-M07-010). */
	private void exigirPersonaDelPadron(long organizationId, long personaId) {
		PacienteSnapshot persona = personas.find(organizationId, personaId)
				.orElseThrow(() -> new PersonaNoAccesibleException(personaId));
		if (!persona.activa()) {
			throw new PersonaDadaDeBajaException(personaId);
		}
	}

	private void auditar(
			OperatingActor actor, PaseServicio pase, ConsultorioSnapshot sede, DeudaDeVenta deuda,
			Instant ahora) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("productoId", String.valueOf(pase.getProductoId()));
		detalles.put("personaId", String.valueOf(pase.getPersonaId()));
		detalles.put("creditos", String.valueOf(pase.getCreditosIniciales()));
		detalles.put("vigenciaHasta", pase.getVigenciaHasta().toString());
		detalles.put("precio", pase.getSnapshotPrecio().toPlainString());
		detalles.put("moneda", pase.getSnapshotMoneda());
		detalles.put("obligacionId", String.valueOf(deuda.obligacionId()));
		if (pase.isRenovacionAutomatica()) {
			detalles.put("renovacionAutomatica", "true");
		}
		auditTrail.record(new AuditEntry(
				pase.getOrganizationId(), sede.id(), actor.accountId(), "PASE_VENDIDO", ENTIDAD,
				pase.getId(), null, pase.getEstado().name(), detalles, null, null, ahora));
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private void exigir(OperatingActor actor, long organizationId, long consultorioId, String permiso) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), permiso, organizationId, consultorioId, null, Instant.now()));
	}
}
