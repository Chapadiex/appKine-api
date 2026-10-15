package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.AjustarSaldoCommand;
import com.akine.activity.application.AjusteDePaseService;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.ConsumoDeCredito.Efecto;
import com.akine.activity.application.CrearProductoCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.PaseDetalleView;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.ResultadoDeAnulacion;
import com.akine.activity.application.ResultadoDeAsistencia;
import com.akine.activity.application.ResultadoDeVenta;
import com.akine.activity.application.VencimientoDePases;
import com.akine.activity.application.VenderPaseCommand;
import com.akine.activity.application.VentaDePaseService;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.TipoProducto;
import com.akine.activity.domain.exception.AbonoPeriodoSuperpuestoException;
import com.akine.activity.domain.exception.OfertaNoAdmitePackException;
import com.akine.activity.domain.exception.PaseConCobrosException;
import com.akine.activity.domain.exception.PaseNoAjustableException;
import com.akine.activity.domain.exception.PaseNotAccessibleException;
import com.akine.activity.domain.exception.TransicionDeClaseNoPermitidaException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AKINE-08.07 / 08.08 contra MySQL real: <b>abono, ajuste manual, anulacion, vencimiento y
 * devolucion por cancelacion de clase</b>, cada uno verificado contra lo que quedo en el motor y
 * con el invariante del ledger —{@code creditos_disponibles == SUM(cantidad)}— despues de cada paso.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PaseAbonoAjusteYVencimientoIT {

	@Autowired private ProductoService productoService;
	@Autowired private VentaDePaseService ventaService;
	@Autowired private AjusteDePaseService ajusteService;
	@Autowired private VencimientoDePases vencimiento;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private AsistenciaService asistenciaService;
	@Autowired private ClaseService claseService;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@Test
	@DisplayName("Abono: un periodo, una obligacion VENTA_ABONO; la asistencia lo consume sin deuda nueva; un periodo superpuesto es 409")
	void abono_por_periodo() {
		Tenant tenant = fx.crearTenant(10, 2);
		declarar(tenant.ofertaId(), "POR_ABONO");
		long productoId = productoService.crear(tenant.actor(), tenant.consultorioId(),
				abono(tenant.ofertaId(), 8, 31)).id();
		LocalDate hoy = hoy();

		ResultadoDeVenta venta = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, tenant.persona(0), null, hoy.minusDays(2), null));
		long paseId = venta.pase().id();

		assertThat(venta.pase().tipo()).isEqualTo("ABONO");
		assertThat(venta.pase().vigenciaDesde()).isEqualTo(hoy.minusDays(2));
		assertThat(venta.pase().vigenciaHasta()).isEqualTo(hoy.plusDays(28));
		Map<String, Object> obligacion = jdbc.queryForMap(
				"SELECT origen, estado, importe_original FROM obligacion WHERE pase_id = ?", paseId);
		assertThat(obligacion.get("origen")).isEqualTo("VENTA_ABONO");
		assertThat(obligacion.get("estado")).isEqualTo("PENDIENTE");
		assertThat(jdbc.queryForObject("SELECT tipo FROM pase_servicio WHERE id = ?", String.class, paseId))
				.isEqualTo("ABONO");

		// RF-M29-008 / RN-M29-007: la asistencia queda cubierta y no nace ninguna deuda por ella.
		long clase = claseEnCurso(tenant, 1);
		ResultadoDeAsistencia presente = registrar(tenant, clase, tenant.persona(0), ResultadoAsistencia.PRESENTE, null);
		assertThat(presente.consumo().efecto()).isEqualTo(Efecto.CONSUMIDO);
		assertThat(saldo(paseId)).isEqualTo(7);
		assertThat(contar("SELECT COUNT(*) FROM obligacion WHERE organization_id = ?", tenant.organizationId()))
				.isEqualTo(1);
		// Sin abono, la otra persona no queda cubierta: se registra igual.
		assertThat(registrar(tenant, clase, tenant.persona(1), ResultadoAsistencia.PRESENTE, null)
				.consumo().efecto()).isEqualTo(Efecto.SIN_PASE_ELEGIBLE);

		// Un periodo, una obligacion: otro abono que se pisa con este es 409 y no escribe nada.
		assertThatThrownBy(() -> ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, tenant.persona(0), null, hoy.plusDays(20), null)))
				.isInstanceOf(AbonoPeriodoSuperpuestoException.class);
		// El periodo siguiente si se vende: renovar es vender el proximo periodo.
		long siguiente = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, tenant.persona(0), null, hoy.plusDays(29), null)).pase().id();
		assertThat(contar("SELECT COUNT(*) FROM obligacion WHERE organization_id = ?", tenant.organizationId()))
				.isEqualTo(2);
		assertThat(sumaDelLedger(siguiente)).isEqualTo(saldo(siguiente));

		// Un abono sobre una oferta POR_PACK no existe, y un pack no lleva periodo.
		Tenant otro = fx.crearTenant(10, 1);
		declarar(otro.ofertaId(), "POR_PACK");
		assertThatThrownBy(() -> productoService.crear(otro.actor(), otro.consultorioId(),
				abono(otro.ofertaId(), 8, 31)))
				.isInstanceOf(OfertaNoAdmitePackException.class);
		long pack = productoService.crear(otro.actor(), otro.consultorioId(),
				PaseVentaYConsumoIT.comando(otro.ofertaId(), "Pack", 4)).id();
		assertThatThrownBy(() -> ventaService.vender(otro.actor(), otro.consultorioId(),
				new VenderPaseCommand(pack, otro.persona(0), null, hoy, null)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("Ajuste manual: suma y resta con motivo, el ledger explica el saldo, el reintento no ajusta dos veces y una version vieja es 409")
	void ajuste_manual() {
		Tenant tenant = tenantPorPack();
		long paseId = venderPack(tenant, 8);

		PaseDetalleView menos = ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), paseId,
				new AjustarSaldoCommand(-3, "Tres clases tomadas antes de cargar el sistema", 0));
		assertThat(menos.pase().creditosDisponibles()).isEqualTo(5);
		long version = menos.pase().version();

		// El reintento del mismo ajuste (misma version leida) no resta otra vez.
		PaseDetalleView reintento = ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), paseId,
				new AjustarSaldoCommand(-3, "Tres clases tomadas antes de cargar el sistema", 0));
		assertThat(reintento.pase().creditosDisponibles()).isEqualTo(5);
		assertThat(contar("SELECT COUNT(*) FROM movimiento_pase WHERE pase_id = ? AND tipo = 'AJUSTE'", paseId))
				.isEqualTo(1);

		// Otro ajuste con la version vieja es una version vieja.
		assertThatThrownBy(() -> ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), paseId,
				new AjustarSaldoCommand(1, "Otro motivo", 0)))
				.isInstanceOf(OptimisticLockingFailureException.class);

		// RN-M29-008 y el techo de lo comprado.
		assertThatThrownBy(() -> ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), paseId,
				new AjustarSaldoCommand(-6, "Demasiado", version)))
				.isInstanceOf(PaseNoAjustableException.class);
		assertThatThrownBy(() -> ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), paseId,
				new AjustarSaldoCommand(4, "Demasiado", version)))
				.isInstanceOf(PaseNoAjustableException.class);

		PaseDetalleView aCero = ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), paseId,
				new AjustarSaldoCommand(-5, "Se dio de baja del taller", version));
		assertThat(aCero.pase().estado()).isEqualTo("AGOTADO");
		PaseDetalleView vuelve = ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), paseId,
				new AjustarSaldoCommand(2, "Volvio al taller", aCero.pase().version()));
		assertThat(vuelve.pase().estado()).isEqualTo("ACTIVO");
		assertThat(saldo(paseId)).isEqualTo(2);
		assertThat(sumaDelLedger(paseId)).isEqualTo(2);

		Map<String, Object> ultimo = jdbc.queryForMap("""
				SELECT tipo, cantidad, saldo_resultante, origen_tipo, actor_cuenta_id, motivo
				  FROM movimiento_pase WHERE pase_id = ? ORDER BY id DESC LIMIT 1
				""", paseId);
		assertThat(ultimo.get("tipo")).isEqualTo("AJUSTE");
		assertThat(ultimo.get("origen_tipo")).isEqualTo("AJUSTE_MANUAL");
		assertThat(ultimo.get("motivo")).isEqualTo("Volvio al taller");
		assertThat(((Number) ultimo.get("actor_cuenta_id")).longValue()).isEqualTo(tenant.actor().accountId());
		// CA-M29-005-06: auditado con actor y motivo. Un credito no es plata: la deuda no se movio.
		assertThat(contar("SELECT COUNT(*) FROM audit_event WHERE event_type = 'PASE_SALDO_AJUSTADO' "
				+ "AND entity_id = " + paseId + " AND organization_id = ?", tenant.organizationId())).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT estado FROM obligacion WHERE pase_id = ?", String.class, paseId))
				.isEqualTo("PENDIENTE");

		// Cross-tenant: el pase de otro centro no existe.
		Tenant otro = fx.crearTenant(10, 1);
		assertThatThrownBy(() -> ajusteService.ajustar(otro.actor(), otro.consultorioId(), paseId,
				new AjustarSaldoCommand(1, "x", vuelve.pase().version())))
				.isInstanceOf(PaseNotAccessibleException.class);
	}

	@Test
	@DisplayName("Anulacion: el pase sin uso se anula con su deuda (que no se borra); con consumos o con cobros no se escribe nada")
	void anulacion() {
		Tenant tenant = tenantPorPack();
		long paseId = venderPack(tenant, 8);

		ResultadoDeAnulacion anulada = ajusteService.anular(
				tenant.actor(), tenant.consultorioId(), paseId, "Se vendio a la persona equivocada", 0);
		assertThat(anulada.anulado()).isTrue();
		assertThat(anulada.pase().estado()).isEqualTo("ANULADO");
		assertThat(anulada.pase().motivoAnulacion()).isEqualTo("Se vendio a la persona equivocada");
		assertThat(anulada.deuda().estado()).isEqualTo("ANULADA");

		Map<String, Object> pase = jdbc.queryForMap("""
				SELECT estado, creditos_disponibles, anulado_en, motivo_anulacion FROM pase_servicio WHERE id = ?
				""", paseId);
		assertThat(pase.get("estado")).isEqualTo("ANULADO");
		assertThat(pase.get("creditos_disponibles")).isEqualTo(0);
		assertThat(pase.get("anulado_en")).isNotNull();
		assertThat(sumaDelLedger(paseId)).isZero();
		// La obligacion no se borra: queda ANULADA, con su motivo.
		Map<String, Object> obligacion = jdbc.queryForMap(
				"SELECT estado, motivo_anulacion, deleted_at FROM obligacion WHERE pase_id = ?", paseId);
		assertThat(obligacion.get("estado")).isEqualTo("ANULADA");
		assertThat(obligacion.get("motivo_anulacion")).isEqualTo("Se vendio a la persona equivocada");
		assertThat(obligacion.get("deleted_at")).isNull();

		// Idempotente: anular de nuevo no escribe.
		ResultadoDeAnulacion otraVez = ajusteService.anular(
				tenant.actor(), tenant.consultorioId(), paseId, "Otra vez", 0);
		assertThat(otraVez.anulado()).isFalse();
		assertThat(contar("SELECT COUNT(*) FROM movimiento_pase WHERE pase_id = ?", paseId)).isEqualTo(2);

		// Con un credito usado no se anula (regalaria la clase tomada).
		long usado = venderPack(tenant, 8);
		long clase = claseEnCurso(tenant, 2);
		registrar(tenant, clase, tenant.persona(0), ResultadoAsistencia.PRESENTE, null);
		long versionUsado = version(usado);
		assertThatThrownBy(() -> ajusteService.anular(
				tenant.actor(), tenant.consultorioId(), usado, "No", versionUsado))
				.isInstanceOf(PaseNoAjustableException.class);
		assertThat(estado(usado)).isEqualTo("ACTIVO");

		// Con cobros imputados no se anula: primero se anula o reintegra el cobro. El UPDATE es el
		// mismo que hace CobroRepository.descontarSaldo al imputar.
		long cobrado = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoDe(usado), tenant.persona(1), null)).pase().id();
		jdbc.update("UPDATE obligacion SET saldo = saldo - 1000, estado = 'PARCIAL', version = version + 1 "
				+ "WHERE pase_id = ?", cobrado);
		assertThatThrownBy(() -> ajusteService.anular(
				tenant.actor(), tenant.consultorioId(), cobrado, "No", 0))
				.isInstanceOf(PaseConCobrosException.class);
		assertThat(estado(cobrado)).isEqualTo("ACTIVO");
		assertThat(jdbc.queryForObject("SELECT estado FROM obligacion WHERE pase_id = ?", String.class, cobrado))
				.isEqualTo("PARCIAL");
	}

	@Test
	@DisplayName("Vencimiento: el job deja VENCIDO, saldo 0 y un VENCIMIENTO que explica el remanente; no vence dos veces")
	void vencimiento_programado() {
		Tenant tenant = tenantPorPack();
		long paseId = venderPack(tenant, 8);
		ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), paseId,
				new AjustarSaldoCommand(-3, "Usadas antes del sistema", 0));
		long vigente = venderPack(tenant, 8);
		// La vigencia no se puede esperar: se corre hacia atras como dato de prueba.
		jdbc.update("UPDATE pase_servicio SET vigencia_desde = DATE_SUB(vigencia_desde, INTERVAL 90 DAY), "
				+ "vigencia_hasta = DATE_SUB(CURDATE(), INTERVAL 2 DAY) WHERE id = ?", paseId);

		vencimiento.vencerLosVencidos(500);
		vencimiento.vencerLosVencidos(500);

		assertThat(estado(paseId)).isEqualTo("VENCIDO");
		assertThat(saldo(paseId)).isZero();
		assertThat(jdbc.queryForList("SELECT tipo FROM movimiento_pase WHERE pase_id = ? ORDER BY id",
				String.class, paseId)).containsExactly("COMPRA", "AJUSTE", "VENCIMIENTO");
		assertThat(jdbc.queryForObject("SELECT cantidad FROM movimiento_pase WHERE pase_id = ? AND tipo = 'VENCIMIENTO'",
				Integer.class, paseId)).isEqualTo(-5);
		assertThat(sumaDelLedger(paseId)).isZero();
		assertThat(estado(vigente)).isEqualTo("ACTIVO");
		// RF-M29-006: vencido no se ajusta. Y la deuda no se toca: un credito no es plata.
		long version = version(paseId);
		assertThatThrownBy(() -> ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), paseId,
				new AjustarSaldoCommand(1, "x", version)))
				.isInstanceOf(PaseNoAjustableException.class);
		assertThat(jdbc.queryForObject("SELECT estado FROM obligacion WHERE pase_id = ?", String.class, paseId))
				.isEqualTo("PENDIENTE");
	}

	@Test
	@DisplayName("Cancelar una clase en curso devuelve el credito de cada asistencia una sola vez, y despues no admite asistencia")
	void devolucion_por_cancelacion() {
		Tenant tenant = tenantPorPack();
		long paseA = venderPack(tenant, 8);
		long paseB = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoDe(paseA), tenant.persona(1), null)).pase().id();
		long clase = claseEnCurso(tenant, 3);
		registrar(tenant, clase, tenant.persona(0), ResultadoAsistencia.PRESENTE, null);
		registrar(tenant, clase, tenant.persona(1), ResultadoAsistencia.PRESENTE, null);
		// Corregida a ausente: ya devolvio el suyo, la cancelacion no le devuelve otro.
		registrar(tenant, clase, tenant.persona(1), ResultadoAsistencia.AUSENTE, "No vino");
		assertThat(saldo(paseA)).isEqualTo(7);
		assertThat(saldo(paseB)).isEqualTo(8);
		long inscripcionA = fx.inscripcionDe(clase, tenant.persona(0));

		claseService.cancelar(tenant.actor(), tenant.consultorioId(), clase, "Se corto la luz");
		claseService.cancelar(tenant.actor(), tenant.consultorioId(), clase, "Reintento");

		assertThat(saldo(paseA)).isEqualTo(8);
		assertThat(saldo(paseB)).isEqualTo(8);
		Map<String, Object> devolucion = jdbc.queryForMap("""
				SELECT tipo, cantidad, origen_tipo, origen_id, motivo FROM movimiento_pase
				 WHERE pase_id = ? AND origen_tipo = 'CANCELACION'
				""", paseA);
		assertThat(devolucion.get("tipo")).isEqualTo("DEVOLUCION");
		assertThat(((Number) devolucion.get("origen_id")).longValue()).isEqualTo(clase);
		assertThat(devolucion.get("motivo")).isEqualTo("Se corto la luz");
		assertThat(contar("SELECT COUNT(*) FROM movimiento_pase WHERE origen_tipo = 'CANCELACION' "
				+ "AND organization_id = ?", tenant.organizationId())).isEqualTo(1);
		assertThat(sumaDelLedger(paseA)).isEqualTo(8);
		assertThat(sumaDelLedger(paseB)).isEqualTo(8);
		// El consumo no se borro: se compenso.
		assertThat(contar("SELECT COUNT(*) FROM movimiento_pase WHERE pase_id = " + paseA
				+ " AND tipo = 'CONSUMO' AND organization_id = ?", tenant.organizationId())).isEqualTo(1);

		assertThatThrownBy(() -> asistenciaService.registrar(tenant.actor(), tenant.consultorioId(), clase,
				new RegistrarAsistenciaCommand(inscripcionA, ResultadoAsistencia.AUSENTE,
						OrigenAsistencia.MOSTRADOR, null, "x")))
				.isInstanceOf(TransicionDeClaseNoPermitidaException.class);
	}

	// =================================================================================

	private Tenant tenantPorPack() {
		Tenant tenant = fx.crearTenant(10, 3);
		declarar(tenant.ofertaId(), "POR_PACK");
		return tenant;
	}

	private void declarar(long ofertaId, String esquema) {
		jdbc.update("UPDATE oferta_servicio_consultorio SET esquema_cobro = ?, momento_devengo = 'VENTA' "
				+ "WHERE id = ?", esquema, ofertaId);
	}

	private static CrearProductoCommand abono(long ofertaId, int asistencias, int dias) {
		return new CrearProductoCommand(ofertaId, "Abono mensual", null, asistencias,
				new BigDecimal("30000.00"), "ARS", dias, LocalDate.now().minusDays(10), null,
				TipoProducto.ABONO);
	}

	private long venderPack(Tenant tenant, int creditos) {
		long productoId = jdbc.query("SELECT id FROM producto_servicio WHERE organization_id = ? AND active = 1",
				rs -> rs.next() ? rs.getLong(1) : 0L, tenant.organizationId());
		if (productoId == 0L) {
			productoId = productoService.crear(tenant.actor(), tenant.consultorioId(),
					PaseVentaYConsumoIT.comando(tenant.ofertaId(), "Pack", creditos)).id();
		}
		return ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, tenant.persona(0), null)).pase().id();
	}

	private long claseEnCurso(Tenant tenant, int diasAdelante) {
		long clase = fx.programarClase(tenant, 10, diasAdelante);
		for (Long persona : tenant.personas()) {
			fx.inscribir(tenant, clase, persona, false);
		}
		asistenciaService.iniciar(tenant.actor(), tenant.consultorioId(), clase);
		return clase;
	}

	private ResultadoDeAsistencia registrar(
			Tenant tenant, long clase, long persona, ResultadoAsistencia resultado, String motivo) {
		return asistenciaService.registrar(tenant.actor(), tenant.consultorioId(), clase,
				new RegistrarAsistenciaCommand(fx.inscripcionDe(clase, persona), resultado,
						OrigenAsistencia.MOSTRADOR, null, motivo));
	}

	private static LocalDate hoy() {
		return LocalDate.now(ZoneId.of(ActividadItFixture.ZONA));
	}

	private long productoDe(long paseId) {
		return jdbc.queryForObject("SELECT producto_id FROM pase_servicio WHERE id = ?", Long.class, paseId);
	}

	private int saldo(long paseId) {
		return jdbc.queryForObject("SELECT creditos_disponibles FROM pase_servicio WHERE id = ?", Integer.class, paseId);
	}

	private String estado(long paseId) {
		return jdbc.queryForObject("SELECT estado FROM pase_servicio WHERE id = ?", String.class, paseId);
	}

	private long version(long paseId) {
		return jdbc.queryForObject("SELECT version FROM pase_servicio WHERE id = ?", Long.class, paseId);
	}

	private int sumaDelLedger(long paseId) {
		return jdbc.queryForObject("SELECT COALESCE(SUM(cantidad), 0) FROM movimiento_pase WHERE pase_id = ?",
				Integer.class, paseId);
	}

	private int contar(String sql, Object arg) {
		return jdbc.queryForObject(sql, Integer.class, arg);
	}
}
