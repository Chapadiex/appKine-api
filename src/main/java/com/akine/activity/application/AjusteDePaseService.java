package com.akine.activity.application;

import com.akine.activity.domain.EstadoPase;
import com.akine.activity.domain.MovimientoPase;
import com.akine.activity.domain.PaseServicio;
import com.akine.activity.domain.PermissionCodes;
import com.akine.activity.domain.exception.ConsultorioNoAccesibleException;
import com.akine.activity.domain.exception.PaseConCobrosException;
import com.akine.activity.domain.exception.PaseNoAjustableException;
import com.akine.activity.domain.exception.PaseNotAccessibleException;
import com.akine.activity.domain.port.ActivityRepositoryPorts.EstadoDePase;
import com.akine.activity.domain.port.ActivityRepositoryPorts.MovimientoPaseRepositoryPort;
import com.akine.activity.domain.port.ActivityRepositoryPorts.PaseServicioRepositoryPort;
import com.akine.billing.spi.DevengoDeVentas;
import com.akine.billing.spi.DevengoDeVentas.AnulacionDeDeuda;
import com.akine.organization.spi.ConsultorioDirectory;
import com.akine.organization.spi.ConsultorioSnapshot;
import com.akine.organization.spi.PermissionGuard;
import com.akine.organization.spi.PermissionQuery;
import com.akine.organization.spi.ZonaDeSede;
import com.akine.platform.spi.audit.AuditEntry;
import com.akine.platform.spi.audit.AuditTrail;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Ajuste manual del saldo y anulacion de un pase (AKINE-08.07, RF-M29-005; RN-M29-003, RN-M29-008,
 * RN-M29-009).
 *
 * <h2>Permiso reforzado</h2>
 *
 * <p>Las dos operaciones exigen {@code pase:adjust}, que no tiene el mostrador: RF-M29-005 pide
 * "permiso especifico" y el plan de 08.07 "permiso reforzado". Vender ({@code pase:manage}) no
 * alcanza para corregir lo vendido.
 *
 * <h2>Concurrencia</h2>
 *
 * <p>El pase se lee bloqueado ({@code FOR UPDATE}, lectura actual) y el cliente manda la version que
 * leyo: un consumo, una devolucion o un vencimiento que entraron en el medio la movieron y la
 * operacion responde {@code concurrent-modification} en vez de pisar el saldo. El {@code UPDATE}
 * nativo repite la condicion de version y rango en su {@code WHERE} y sube la version.
 * {@code READ_COMMITTED}, como toda mutacion de este repositorio que bloquea y relee.
 *
 * <h2>Idempotencia sin clave</h2>
 *
 * <p>El ajuste se identifica por la version sobre la que se aplico ({@code origen_id} del
 * movimiento): el reintento del mismo pedido encuentra su propio movimiento y devuelve el estado
 * actual sin ajustar dos veces; un pedido distinto con la misma version es una version vieja.
 * La anulacion es idempotente por estado: anular un {@code ANULADO} no escribe.
 */
@Service
public class AjusteDePaseService {

	private static final Logger log = LoggerFactory.getLogger(AjusteDePaseService.class);

	private static final String ENTIDAD = "PASE_SERVICIO";

	private final PaseServicioRepositoryPort pases;
	private final MovimientoPaseRepositoryPort movimientos;
	private final DevengoDeVentas ventas;
	private final ConsultorioDirectory consultorios;
	private final PermissionGuard permissionGuard;
	private final AuditTrail auditTrail;
	private final Clock clock;

	@Autowired
	public AjusteDePaseService(
			PaseServicioRepositoryPort pases,
			MovimientoPaseRepositoryPort movimientos,
			DevengoDeVentas ventas,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail) {

		this(pases, movimientos, ventas, consultorios, permissionGuard, auditTrail, Clock.systemUTC());
	}

	AjusteDePaseService(
			PaseServicioRepositoryPort pases,
			MovimientoPaseRepositoryPort movimientos,
			DevengoDeVentas ventas,
			ConsultorioDirectory consultorios,
			PermissionGuard permissionGuard,
			AuditTrail auditTrail,
			Clock clock) {

		this.pases = pases;
		this.movimientos = movimientos;
		this.ventas = ventas;
		this.consultorios = consultorios;
		this.permissionGuard = permissionGuard;
		this.auditTrail = auditTrail;
		this.clock = clock;
	}

	/**
	 * Suma o resta creditos con motivo (RF-M29-005). Devuelve el pase con su ledger.
	 *
	 * @throws PaseNotAccessibleException el pase no existe en la sede (404)
	 * @throws PaseNoAjustableException   anulado, vencido o saldo final fuera de [0, iniciales] (409)
	 * @throws OptimisticLockingFailureException la version no es la leida (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public PaseDetalleView ajustar(
			OperatingActor actor, long consultorioId, long paseId, AjustarSaldoCommand command) {

		long organizationId = ProductoService.exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirAjuste(actor, organizationId, consultorioId);
		if (command.cantidad() == 0) {
			throw new IllegalArgumentException("Un ajuste de cero creditos no cambia nada");
		}
		if (command.motivo() == null || command.motivo().isBlank()) {
			throw new IllegalArgumentException("El ajuste de saldo exige motivo (RF-M29-005)");
		}
		LocalDate hoy = ZonaDeSede.hoy(sede, clock);
		PaseServicio pase = pases.lockByIdInScope(organizationId, consultorioId, paseId)
				.orElseThrow(() -> new PaseNotAccessibleException(paseId));

		// El reintento del mismo ajuste: su movimiento ya existe sobre esa version.
		Optional<MovimientoPase> previo =
				movimientos.ajusteSobreVersion(organizationId, paseId, command.version());
		if (previo.isPresent() && previo.get().getCantidad() == command.cantidad()
				&& command.motivo().strip().equals(previo.get().getMotivo())) {
			return detalle(pase, organizationId, hoy);
		}
		exigirVersion(pase, command.version());
		if (pase.getEstado() == EstadoPase.ANULADO || pase.getEstado() == EstadoPase.VENCIDO) {
			throw new PaseNoAjustableException(paseId, PaseNoAjustableException.Causa.ESTADO,
					"esta " + pase.getEstado().name().toLowerCase());
		}
		if (pase.getVigenciaHasta().isBefore(hoy)) {
			// Vencido por fecha aunque el job todavia no lo haya escrito: un credito sumado hoy no
			// lo podria usar nadie, y uno restado cambia lo que el vencimiento va a explicar.
			throw new PaseNoAjustableException(paseId, PaseNoAjustableException.Causa.FUERA_DE_VIGENCIA,
					"su vigencia termino el " + pase.getVigenciaHasta());
		}
		int saldoAnterior = pase.getCreditosDisponibles();
		int saldoFinal = saldoAnterior + command.cantidad();
		if (saldoFinal < 0 || saldoFinal > pase.getCreditosIniciales()) {
			throw new PaseNoAjustableException(paseId, PaseNoAjustableException.Causa.FUERA_DE_RANGO,
					"el saldo quedaria en " + saldoFinal
					+ " y tiene que estar entre 0 y los " + pase.getCreditosIniciales() + " creditos comprados");
		}
		if (pases.ajustar(organizationId, paseId, command.cantidad(), command.version()) != 1) {
			throw versionVieja(paseId, command.version());
		}

		Instant ahora = clock.instant();
		EstadoDePase actual = pases.estadoActual(organizationId, paseId);
		movimientos.registrar(MovimientoPase.ajusteManual(
				organizationId, paseId, command.cantidad(), actual.saldo(), command.version(),
				actor.accountId(), command.motivo(), ahora));

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("cantidad", String.valueOf(command.cantidad()));
		detalles.put("saldoAnterior", String.valueOf(saldoAnterior));
		detalles.put("saldo", String.valueOf(actual.saldo()));
		auditar(organizationId, consultorioId, actor.accountId(), "PASE_SALDO_AJUSTADO", paseId,
				pase.getEstado().name(), actual.estado(), detalles, command.motivo().strip(), ahora);
		log.info("Saldo de pase ajustado: paseId={} cantidad={} saldo={}",
				paseId, command.cantidad(), actual.saldo());

		return new PaseDetalleView(
				PaseView.de(pase, actual.saldo(), actual.estado(), actual.version(), hoy,
						PaseView.Uso.de(movimientos, ventas, organizationId, paseId)),
				historial(organizationId, paseId));
	}

	/**
	 * Anula un pase vendido por error (RN-M29-002, RN-M29-009): estado {@code ANULADO}, el saldo
	 * remanente descartado con un movimiento y <b>la deuda de la venta anulada</b> en la misma
	 * transaccion, por {@code billing.spi}. La obligacion no se borra: queda {@code ANULADA} con su
	 * motivo, como cualquier anulacion de {@code billing}.
	 *
	 * <p><b>Solo un pase sin uso.</b> Con creditos consumidos, anularlo y anular la deuda regalaria
	 * las clases tomadas, y prorratearlas es una politica que ningun RF define: queda el ajuste
	 * manual. <b>Y solo una deuda sin cobros</b>: con plata imputada, primero se anula o reintegra el
	 * cobro (RF-M19-010), que es un acto de caja. En los dos casos no se escribe nada.
	 *
	 * @throws PaseNoAjustableException tiene creditos usados (409)
	 * @throws PaseConCobrosException   la deuda tiene cobros imputados (409)
	 * @throws OptimisticLockingFailureException la version no es la leida (409)
	 */
	@Transactional(isolation = Isolation.READ_COMMITTED)
	public ResultadoDeAnulacion anular(
			OperatingActor actor, long consultorioId, long paseId, String motivo, long version) {

		long organizationId = ProductoService.exigirContexto(actor);
		ConsultorioSnapshot sede = exigirSedeDelTenant(organizationId, consultorioId);
		exigirAjuste(actor, organizationId, consultorioId);
		if (motivo == null || motivo.isBlank()) {
			throw new IllegalArgumentException("La anulacion de un pase exige motivo");
		}
		String motivoLimpio = motivo.strip();
		LocalDate hoy = ZonaDeSede.hoy(sede, clock);
		PaseServicio pase = pases.lockByIdInScope(organizationId, consultorioId, paseId)
				.orElseThrow(() -> new PaseNotAccessibleException(paseId));
		Instant ahora = clock.instant();

		if (pase.getEstado() == EstadoPase.ANULADO) {
			// Idempotente: la deuda ya quedo anulada con el pase; billing lo informa sin escribir.
			AnulacionDeDeuda deuda = ventas.anularDeudaDeVenta(
					organizationId, paseId, actor.accountId(), motivoLimpio, ahora);
			return new ResultadoDeAnulacion(
					PaseView.de(pase, hoy, PaseView.Uso.de(movimientos, ventas, organizationId, paseId)),
					deuda(deuda), false);
		}
		exigirVersion(pase, version);
		int usados = -movimientos.consumoNeto(organizationId, paseId);
		if (usados > 0) {
			throw new PaseNoAjustableException(paseId, PaseNoAjustableException.Causa.CREDITOS_USADOS,
					"tiene " + usados
					+ " creditos usados por asistencias; para descartar el resto se ajusta el saldo");
		}

		AnulacionDeDeuda deuda = ventas.anularDeudaDeVenta(
				organizationId, paseId, actor.accountId(), motivoLimpio, ahora);
		if (deuda.efecto() == AnulacionDeDeuda.Efecto.CON_COBROS) {
			throw new PaseConCobrosException(
					paseId, deuda.obligacionId(), deuda.importe().subtract(deuda.saldo()), deuda.moneda());
		}

		int descartados = pase.getCreditosDisponibles();
		if (pases.anular(organizationId, paseId, version, actor.accountId(), motivoLimpio, ahora) != 1) {
			throw versionVieja(paseId, version);
		}
		if (descartados > 0) {
			movimientos.registrar(MovimientoPase.ajusteManual(
					organizationId, paseId, -descartados, 0, version, actor.accountId(),
					"Anulacion: " + motivoLimpio, ahora));
		}
		EstadoDePase actual = pases.estadoActual(organizationId, paseId);

		Map<String, String> detalles = new LinkedHashMap<>();
		detalles.put("creditosDescartados", String.valueOf(descartados));
		if (deuda.obligacionId() != null) {
			detalles.put("obligacionId", String.valueOf(deuda.obligacionId()));
			detalles.put("deuda", deuda.efecto().name());
		}
		auditar(organizationId, consultorioId, actor.accountId(), "PASE_ANULADO", paseId,
				pase.getEstado().name(), actual.estado(), detalles, motivoLimpio, ahora);
		log.info("Pase anulado: paseId={} descartados={} deuda={}", paseId, descartados, deuda.efecto());

		return new ResultadoDeAnulacion(
				PaseView.de(pase, actual.saldo(), actual.estado(), actual.version(), hoy,
						new PaseView.Uso(usados, false))
						.anulado(ahora, actor.accountId(), motivoLimpio),
				deuda(deuda), true);
	}

	// =================================================================================

	private PaseDetalleView detalle(PaseServicio pase, long organizationId, LocalDate hoy) {
		return new PaseDetalleView(
				PaseView.de(pase, hoy, PaseView.Uso.de(movimientos, ventas, organizationId, pase.getId())),
				historial(organizationId, pase.getId()));
	}

	private java.util.List<MovimientoPaseView> historial(long organizationId, long paseId) {
		return movimientos.historial(organizationId, paseId).stream().map(MovimientoPaseView::de).toList();
	}

	private static ResultadoDeVenta.Deuda deuda(AnulacionDeDeuda d) {
		if (d.obligacionId() == null) {
			return null;
		}
		return new ResultadoDeVenta.Deuda(d.obligacionId(), d.importe(), d.saldo(), d.moneda(), d.estado());
	}

	private static void exigirVersion(PaseServicio pase, long version) {
		if (pase.getVersion() != version) {
			throw versionVieja(pase.getId(), version);
		}
	}

	private static OptimisticLockingFailureException versionVieja(long paseId, long version) {
		return new OptimisticLockingFailureException(
				"El pase " + paseId + " cambio desde que se leyo (version " + version + ")");
	}

	private void auditar(
			long organizationId, long consultorioId, long actorCuentaId, String evento, long paseId,
			String anterior, String nuevo, Map<String, String> detalles, String motivo, Instant ahora) {

		auditTrail.record(new AuditEntry(
				organizationId, consultorioId, actorCuentaId, evento, ENTIDAD, paseId, anterior, nuevo,
				detalles, motivo, null, ahora));
	}

	private ConsultorioSnapshot exigirSedeDelTenant(long organizationId, long consultorioId) {
		return consultorios.find(organizationId, consultorioId)
				.orElseThrow(() -> new ConsultorioNoAccesibleException(consultorioId));
	}

	private void exigirAjuste(OperatingActor actor, long organizationId, long consultorioId) {
		permissionGuard.requirePermission(new PermissionQuery(
				actor.accountId(), PermissionCodes.PASE_ADJUST, organizationId, consultorioId, null,
				Instant.now()));
	}
}
