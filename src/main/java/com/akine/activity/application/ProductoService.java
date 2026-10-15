package com.akine.activity.application;

import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.ProductoServicio;
import com.akine.activity.domain.TipoProducto;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.OfertaNoAdmitePackException;
import com.akine.activity.domain.exception.OfertaNotAccessibleException;
import com.akine.activity.domain.exception.ProductoNombreEnUsoException;
import com.akine.activity.domain.exception.ProductoNotAccessibleException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.ProductoServicioRepositoryPort;
import com.akine.offering.spi.EsquemaDeCobro;
import com.akine.offering.spi.OfertaDirectory;
import com.akine.offering.spi.OfertaSnapshot;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogo de productos de pack de una sede (AKINE-08.06, RF-M29-001).
 *
 * <p>Crear un producto <b>no crea saldo a nadie</b> (CA-M29-001-06): el saldo nace con la venta,
 * en {@link VentaDePaseService}.
 *
 * <p>El producto cuelga de una oferta de la sede que declara {@code esquema_cobro = POR_PACK} (un
 * pack) o {@code POR_ABONO} (un abono, 08.08): esa es la politica que despues decide si una
 * asistencia consume credito (RN-M29-005).
 */
@Service
public class ProductoService {

	private static final Logger log = LoggerFactory.getLogger(ProductoService.class);

	static final String ENTIDAD = "PRODUCTO_SERVICIO";

	private final ProductoServicioRepositoryPort productos;
	private final OfertaDirectory ofertas;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;

	public ProductoService(
			ProductoServicioRepositoryPort productos,
			OfertaDirectory ofertas,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this.productos = productos;
		this.ofertas = ofertas;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
	}

	/**
	 * @throws OfertaNotAccessibleException  la oferta no existe en la sede (404)
	 * @throws OfertaNoAdmitePackException   la oferta esta de baja o no cobra POR_PACK / POR_ABONO (409)
	 * @throws ProductoNombreEnUsoException  ya hay un producto vigente con ese nombre (409)
	 */
	@Transactional
	public ProductoView crear(OperatingActor actor, long consultorioId, CrearProductoCommand command) {
		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.PRODUCTO_MANAGE);

		TipoProducto tipo = command.tipoEfectivo();
		exigirOfertaQueAdmite(organizationId, consultorioId, command.ofertaId(), tipo);

		ProductoServicio nuevo = tipo == TipoProducto.ABONO
				? ProductoServicio.abono(
						organizationId, consultorioId, command.ofertaId(), command.nombre(),
						command.descripcion(), command.creditos(), command.precio(), command.moneda(),
						command.vigenciaDias(), command.vigenciaDesde(), command.vigenciaHasta())
				: ProductoServicio.pack(
						organizationId, consultorioId, command.ofertaId(), command.nombre(),
						command.descripcion(), command.creditos(), command.precio(), command.moneda(),
						command.vigenciaDias(), command.vigenciaDesde(), command.vigenciaHasta());
		exigirNombreLibre(organizationId, consultorioId, nuevo.getNombre(), 0L);

		ProductoServicio guardado = productos.saveAndFlush(nuevo);
		auditar(actor, guardado, sede, "PRODUCTO_CREADO", null, null);
		log.info("Producto creado: productoId={} consultorioId={} ofertaId={}",
				guardado.getId(), consultorioId, guardado.getOfertaId());
		return ProductoView.de(guardado);
	}

	@Transactional(readOnly = true)
	public List<ProductoView> listar(OperatingActor actor, long consultorioId, boolean incluirBajas) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.PRODUCTO_READ);
		return productos.findDeLaSede(organizationId, consultorioId, incluirBajas).stream()
				.map(ProductoView::de)
				.toList();
	}

	@Transactional(readOnly = true)
	public ProductoView find(OperatingActor actor, long consultorioId, long productoId) {
		long organizationId = exigirContexto(actor);
		exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.PRODUCTO_READ);
		return ProductoView.de(exigirProducto(organizationId, consultorioId, productoId));
	}

	/** Editar el producto no toca los pases ya vendidos: llevan su snapshot. */
	@Transactional
	public ProductoView editar(
			OperatingActor actor, long consultorioId, long productoId, EditarProductoCommand command) {

		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.PRODUCTO_MANAGE);

		ProductoServicio producto = exigirProducto(organizationId, consultorioId, productoId);
		if (producto.getVersion() != command.version()) {
			throw new OptimisticLockingFailureException(
					"El producto " + productoId + " fue modificado por otra operacion");
		}
		producto.editar(command.nombre(), command.descripcion(), command.creditos(),
				command.precio(), command.moneda(), command.vigenciaDias(), command.vigenciaDesde(),
				command.vigenciaHasta(), command.limpiarVigenciaHasta());
		exigirNombreLibre(organizationId, consultorioId, producto.getNombre(), productoId);

		ProductoServicio guardado = productos.saveAndFlush(producto);
		auditar(actor, guardado, sede, "PRODUCTO_EDITADO", "ACTIVO", null);
		return ProductoView.de(guardado);
	}

	/** Baja logica. <b>No cascadea</b>: los pases vendidos siguen activos con su saldo. Idempotente. */
	@Transactional
	public ProductoView darDeBaja(OperatingActor actor, long consultorioId, long productoId, String motivo) {
		long organizationId = exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigir(actor, organizationId, consultorioId, PermissionCodes.PRODUCTO_MANAGE);

		ProductoServicio producto = exigirProducto(organizationId, consultorioId, productoId);
		if (!producto.darDeBaja(motivo, Instant.now())) {
			return ProductoView.de(producto);
		}
		ProductoServicio guardado = productos.saveAndFlush(producto);
		auditar(actor, guardado, sede, "PRODUCTO_DADO_DE_BAJA", "ACTIVO",
				guardado.getDeactivationReason());
		log.info("Producto dado de baja: productoId={}", productoId);
		return ProductoView.de(guardado);
	}

	// =================================================================================

	/**
	 * Un pack exige una oferta {@code POR_PACK} y un abono una {@code POR_ABONO}: el esquema de la
	 * oferta es la politica que despues decide que una asistencia consume credito (RN-M29-005) y que
	 * no devenga por asistencia (RN-M29-007). Cruzarlos dejaria un credito que ninguna asistencia
	 * consume o, peor, uno que conviva con el devengo por atencion.
	 */
	private void exigirOfertaQueAdmite(
			long organizationId, long consultorioId, long ofertaId, TipoProducto tipo) {
		OfertaSnapshot oferta = ofertas.find(organizationId, consultorioId, ofertaId)
				.orElseThrow(() -> new OfertaNotAccessibleException(ofertaId));
		if (!oferta.active()) {
			throw new OfertaNoAdmitePackException(ofertaId, "esta dada de baja");
		}
		EsquemaDeCobro exigido = tipo == TipoProducto.ABONO ? EsquemaDeCobro.POR_ABONO : EsquemaDeCobro.POR_PACK;
		boolean admite = ofertas.esquemaDeCobro(organizationId, consultorioId, ofertaId)
				.filter(exigido::equals)
				.isPresent();
		if (!admite) {
			throw new OfertaNoAdmitePackException(
					ofertaId, "no declara el esquema de cobro " + exigido.name());
		}
	}

	private void exigirNombreLibre(long organizationId, long consultorioId, String nombre, long excluir) {
		if (productos.existeVigenteConNombre(organizationId, consultorioId, nombre, excluir)) {
			throw new ProductoNombreEnUsoException(nombre);
		}
	}

	private ProductoServicio exigirProducto(long organizationId, long consultorioId, long productoId) {
		return productos.findByIdInScope(organizationId, consultorioId, productoId)
				.orElseThrow(() -> new ProductoNotAccessibleException(productoId));
	}

	/** Dentro de la transaccion: un registro post-commit que falla deja el cambio sin rastro. */
	private void auditar(
			OperatingActor actor, ProductoServicio producto, ConsultorioSnapshot sede,
			String evento, String anterior, String motivo) {

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("ofertaId", String.valueOf(producto.getOfertaId()));
		detalles.put("creditos", String.valueOf(producto.getCreditos()));
		detalles.put("precio", producto.getPrecio().toPlainString());
		detalles.put("moneda", producto.getMoneda());
		auditTrail.record(new AuditEntry(
				producto.getOrganizationId(), sede.id(), actor.accountId(), evento, ENTIDAD,
				producto.getId(), anterior, producto.isActive() ? "ACTIVO" : "BAJA", detalles,
				motivo, null, Instant.now()));
	}

	/** Falta de contexto es <b>403 y nunca 401</b>: un 401 deja al frontend en bucle de login. */
	static long exigirContexto(OperatingActor actor) {
		if (actor == null || actor.contextOrganizationId() == null) {
			throw new AccessDeniedException(
					"La gestion de productos y pases requiere un contexto de trabajo activo");
		}
		return actor.contextOrganizationId();
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
