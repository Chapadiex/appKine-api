package com.akine.activity.application;

import com.akine.activity.domain.MovimientoPase;
import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.ProductoServicio;
import com.akine.activity.domain.TipoProducto;
import com.akine.activity.domain.exception.AbonoPeriodoSuperpuestoException;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

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
 * es 409. {@code uk_pase_idempotency} cubre la carrera entre dos pedidos simultaneos, y
 * {@code uk_obligacion_venta} es el cinturon del lado de {@code billing}.
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
			AuditTrail auditTrail) {

		this(pases, productos, movimientos, devengo, personas, consultorios, permissionGuard,
				auditTrail, Clock.systemUTC());
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
			Clock clock) {

		this.pases = pases;
		this.productos = productos;
		this.movimientos = movimientos;
		this.devengo = devengo;
		this.personas = personas;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
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
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ResultadoDeVenta vender(OperatingActor actor, long consultorioId, VenderPaseCommand command) {
		long organizationId = ProductoService.exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.PASE_MANAGE);
		LocalDate hoy = ZonaDeSede.hoy(sede, clock);

		String huella = command.idempotencyKey() == null ? null : command.huella(consultorioId);
		Optional<PaseServicio> yaVendido = command.idempotencyKey() == null
				? Optional.empty()
				: pases.findByIdempotencyKey(organizationId, command.idempotencyKey());
		if (yaVendido.isPresent()) {
			PaseServicio existente = yaVendido.get();
			if (!huella.equals(existente.getRequestHash())) {
				throw new IdempotencyKeyConflictException(command.idempotencyKey());
			}
			// El devengo es idempotente por pase: devuelve la deuda que ya existe sin escribir.
			DeudaDeVenta deuda = devengar(existente, actor);
			return new ResultadoDeVenta(PaseView.de(existente, hoy), deuda(deuda), false);
		}

		// El producto se lee BLOQUEADO (AKINE-08.08): lectura actual —una baja que commiteo recien
		// se ve— y, en un abono, serializa las ventas del mismo producto, que es lo que hace cumplir
		// "un periodo, una obligacion": dos periodos superpuestos vendidos a la vez no comparten un
		// valor de columna que un unique pueda ver. Orden: producto_servicio -> pase_servicio.
		ProductoServicio producto = productos
				.lockByIdInScope(organizationId, consultorioId, command.productoId())
				.orElseThrow(() -> new ProductoNotAccessibleException(command.productoId()));
		boolean abono = producto.getTipo() == TipoProducto.ABONO;
		if (!abono && (command.periodoDesde() != null || command.periodoHasta() != null)) {
			throw new IllegalArgumentException(
					"Un pack vale desde la compra: el periodo es solo para abonos");
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
		PaseServicio pase = pases.saveAndFlush(nuevo);
		movimientos.registrar(MovimientoPase.compra(pase, actor.accountId(), ahora));
		DeudaDeVenta deuda = devengar(pase, actor);

		auditar(actor, pase, sede, deuda, ahora);
		log.info("Pase vendido: paseId={} productoId={} personaId={} creditos={} obligacionId={}",
				pase.getId(), producto.getId(), command.personaId(), pase.getCreditosIniciales(),
				deuda.obligacionId());
		return new ResultadoDeVenta(PaseView.de(pase, hoy), deuda(deuda), true);
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
				PaseView.de(pase, ZonaDeSede.hoy(sede, clock)),
				movimientos.historial(organizationId, paseId).stream()
						.map(MovimientoPaseView::de)
						.toList());
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
		return pases.findDePersona(organizationId, personaId).stream()
				.filter(pase -> pase.getConsultorioId() == consultorioId)
				.map(pase -> PaseView.de(pase, hoy))
				.toList();
	}

	// =================================================================================

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
