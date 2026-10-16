package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.AjusteDePaseService;
import com.akine.activity.application.CrearProductoCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.PaseDetalleView;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.ResultadoDeAnulacion;
import com.akine.activity.application.ResultadoDeVenta;
import com.akine.activity.application.VenderPaseCommand;
import com.akine.activity.application.VentaDePaseService;
import com.akine.activity.domain.TipoProducto;
import com.akine.activity.domain.exception.PaseConCobrosException;
import com.akine.billing.application.CobroCommand;
import com.akine.billing.application.CobroPosteriorService;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.CobroView;
import com.akine.billing.application.ObligacionService;
import com.akine.billing.application.ObligacionView;
import com.akine.billing.application.OperatingActor;
import com.akine.billing.application.PresentacionCommands;
import com.akine.billing.application.PresentacionService;
import com.akine.billing.application.PresentacionView;
import com.akine.billing.domain.MedioDePago;
import com.akine.billing.domain.exception.ObligacionDeVentaException;
import com.akine.billing.domain.exception.ObligacionNoPresentableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AKINE-08.09 contra MySQL real: <b>la venta de un pase recorre el circuito economico del MVP</b>
 * —deuda, cobro, caja, anulacion del cobro, anulacion de la venta y presentaciones— sin atajos.
 *
 * <p>Hasta esta etapa el cobro de una venta solo se habia simulado con un UPDATE sobre el saldo
 * ({@code PaseAbonoAjusteYVencimientoIT}): ningun test hacia pasar la deuda de una venta por
 * {@code CobroService}, por la jornada de caja ni por la reversion de {@code CobroPosteriorService}.
 * Aca cada paso se verifica contra lo que quedo en el motor.
 *
 * <p>Las tres reglas maestras se ven por separado: la deuda nace con la venta (M18), el cobro la
 * descuenta (M19) y la caja registra la plata (M20); anular el cobro revierte la caja y devuelve la
 * deuda, y recien entonces la venta se puede anular, con su deuda, sin borrar nada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class VentaDePaseEnElCircuitoEconomicoIT {

	private static final BigDecimal PRECIO = new BigDecimal("40000.00");
	private static final BigDecimal MITAD = new BigDecimal("20000.00");

	@Autowired private ProductoService productoService;
	@Autowired private VentaDePaseService ventaService;
	@Autowired private AjusteDePaseService ajusteService;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private CobroService cobroService;
	@Autowired private CobroPosteriorService posteriorService;
	@Autowired private ObligacionService obligacionService;
	@Autowired private PresentacionService presentacionService;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@Test
	@DisplayName("Venta -> cobro en efectivo entra a la caja; con cobro no se anula; anular el cobro revierte la caja y deja anular la venta")
	void cobro_caja_y_anulacion_de_una_venta() {
		Tenant tenant = tenantPorPack();
		OperatingActor caja = actorDeBilling(tenant);
		abrirCaja(tenant);

		ResultadoDeVenta venta = vender(tenant, 0);
		long paseId = venta.pase().id();
		long obligacionId = venta.deuda().obligacionId();

		// M18: la deuda esta en la cuenta corriente como venta de pase, sin cobro ni caja.
		ObligacionView deuda = unicaDeuda(tenant, caja, 0);
		assertThat(deuda.id()).isEqualTo(obligacionId);
		assertThat(deuda.origen()).isEqualTo("VENTA_PASE");
		assertThat(deuda.paseId()).isEqualTo(paseId);
		assertThat(deuda.sesionId()).isNull();
		assertThat(deuda.saldo()).isEqualByComparingTo(PRECIO);
		assertThat(saldoDeCaja(tenant)).isEqualByComparingTo(BigDecimal.ZERO);

		// M19 + M20: cobrar la mitad en efectivo descuenta la deuda y mete la plata en la jornada.
		CobroView cobro = cobrar(tenant, caja, 0, obligacionId, MITAD);
		assertThat(cobro.comprobanteNumero()).isPositive();
		assertThat(estadoDeDeuda(obligacionId)).isEqualTo("PARCIAL");
		assertThat(saldoDeDeuda(obligacionId)).isEqualByComparingTo(MITAD);
		assertThat(saldoDeCaja(tenant)).isEqualByComparingTo(MITAD);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM movimiento_caja
				 WHERE organization_id = ? AND tipo_origen = 'COBRO' AND referencia_origen = ? AND tipo = 'INGRESO'
				""", Integer.class, tenant.organizationId(), cobro.id())).isEqualTo(1);

		// Con cobros la venta no se anula, y lo que se lee por el pase dice cuanto se cobro.
		PaseDetalleView leido = ventaService.find(tenant.actor(), tenant.consultorioId(), paseId);
		assertThat(leido.pase().anulable()).isFalse();
		assertThatThrownBy(() -> ajusteService.anular(
				tenant.actor(), tenant.consultorioId(), paseId, "Se arrepintio", version(paseId)))
				.isInstanceOfSatisfying(PaseConCobrosException.class,
						e -> assertThat(e.getCobrado()).isEqualByComparingTo(MITAD));

		// Y la deuda sola tampoco se anula desde la cuenta corriente (08.09): dejaria el pase vivo.
		long versionDeuda = versionDeDeuda(obligacionId);
		assertThatThrownBy(() -> obligacionService.anular(
				caja, tenant.consultorioId(), obligacionId, "Cortesia", versionDeuda))
				.isInstanceOfSatisfying(ObligacionDeVentaException.class,
						e -> assertThat(e.getPaseId()).isEqualTo(paseId));
		assertThat(estadoDeDeuda(obligacionId)).isEqualTo("PARCIAL");
		assertThat(estadoDelPase(paseId)).isEqualTo("ACTIVO");

		// Anular el cobro revierte la caja y devuelve la deuda entera: el circuito queda como antes.
		CobroView anulado = posteriorService.anular(
				caja, tenant.consultorioId(), cobro.id(), "Se cobro a cuenta de otra persona");
		assertThat(anulado.estado()).isEqualTo(CobroView.ANULADO);
		assertThat(saldoDeCaja(tenant)).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(estadoDeDeuda(obligacionId)).isEqualTo("PENDIENTE");
		assertThat(saldoDeDeuda(obligacionId)).isEqualByComparingTo(PRECIO);
		assertThat(ventaService.find(tenant.actor(), tenant.consultorioId(), paseId).pase().anulable()).isTrue();

		// Ahora si: la venta se anula con su deuda, en una sola transaccion y sin borrar nada.
		ResultadoDeAnulacion anulacion = ajusteService.anular(
				tenant.actor(), tenant.consultorioId(), paseId, "Venta cargada por error", version(paseId));
		assertThat(anulacion.anulado()).isTrue();
		assertThat(anulacion.deuda().estado()).isEqualTo("ANULADA");
		assertThat(estadoDelPase(paseId)).isEqualTo("ANULADO");
		assertThat(estadoDeDeuda(obligacionId)).isEqualTo("ANULADA");
		assertThat(jdbc.queryForObject("SELECT deleted_at FROM obligacion WHERE id = ?",
				Timestamp.class, obligacionId)).isNull();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cobro_imputacion WHERE cobro_id = ?",
				Integer.class, cobro.id())).as("la imputacion historica sigue").isEqualTo(1);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM movimiento_caja WHERE organization_id = ?
				""", Integer.class, tenant.organizationId())).as("ingreso y su reversion").isEqualTo(2);
		assertThat(unicaDeuda(tenant, caja, 0).estado()).isEqualTo("ANULADA");
	}

	@Test
	@DisplayName("Una venta pagada entera queda PAGADA y no es anulable; un abono pasa por la misma caja")
	void cobro_total_de_pack_y_de_abono() {
		Tenant tenant = tenantPorPack();
		OperatingActor caja = actorDeBilling(tenant);
		abrirCaja(tenant);

		ResultadoDeVenta pack = vender(tenant, 0);
		cobrar(tenant, caja, 0, pack.deuda().obligacionId(), PRECIO);
		assertThat(estadoDeDeuda(pack.deuda().obligacionId())).isEqualTo("PAGADA");
		assertThat(ventaService.find(tenant.actor(), tenant.consultorioId(), pack.pase().id()).pase().anulable())
				.isFalse();
		// Pagar la venta no toca los creditos: el pase sigue con los ocho que compro.
		assertThat(jdbc.queryForObject("SELECT creditos_disponibles FROM pase_servicio WHERE id = ?",
				Integer.class, pack.pase().id())).isEqualTo(8);

		assertThat(saldoDeCaja(tenant)).isEqualByComparingTo(PRECIO);

		// El abono es un pase con periodo: su deuda VENTA_ABONO se cobra por la misma caja.
		Tenant conAbono = fx.crearTenant(10, 1);
		jdbc.update("UPDATE oferta_servicio_consultorio SET esquema_cobro = 'POR_ABONO', "
				+ "momento_devengo = 'VENTA' WHERE id = ?", conAbono.ofertaId());
		OperatingActor cajaAbono = actorDeBilling(conAbono);
		abrirCaja(conAbono);
		long productoAbono = productoService.crear(conAbono.actor(), conAbono.consultorioId(),
				new CrearProductoCommand(conAbono.ofertaId(), "Abono mensual", null, 12,
						new BigDecimal("30000.00"), "ARS", 30, LocalDate.now().minusDays(10), null,
						TipoProducto.ABONO)).id();
		ResultadoDeVenta abono = ventaService.vender(conAbono.actor(), conAbono.consultorioId(),
				new VenderPaseCommand(productoAbono, conAbono.persona(0), null,
						LocalDate.now(ZoneId.of(ActividadItFixture.ZONA)), null));
		assertThat(jdbc.queryForObject("SELECT origen FROM obligacion WHERE id = ?", String.class,
				abono.deuda().obligacionId())).isEqualTo("VENTA_ABONO");
		cobrar(conAbono, cajaAbono, 0, abono.deuda().obligacionId(), new BigDecimal("30000.00"));
		assertThat(estadoDeDeuda(abono.deuda().obligacionId())).isEqualTo("PAGADA");
		assertThat(saldoDeCaja(conAbono)).isEqualByComparingTo(new BigDecimal("30000.00"));
		assertThatThrownBy(() -> obligacionService.anular(cajaAbono, conAbono.consultorioId(),
				abono.deuda().obligacionId(), "Cortesia", versionDeDeuda(abono.deuda().obligacionId())))
				.isInstanceOfSatisfying(ObligacionDeVentaException.class,
						e -> assertThat(e.getOrigen()).isEqualTo("VENTA_ABONO"));
	}

	@Test
	@DisplayName("La deuda de una venta no entra a una presentacion: no es elegible ni se puede agregar a mano (CA-M21-010-06)")
	void la_venta_no_se_presenta_al_financiador() {
		Tenant tenant = tenantPorPack();
		OperatingActor caja = actorDeBilling(tenant);
		long obligacionId = vender(tenant, 0).deuda().obligacionId();
		long financiadorId = insertarFinanciador(tenant.organizationId());

		LocalDate hoy = LocalDate.now(ZoneId.of(ActividadItFixture.ZONA));
		assertThat(presentacionService.elegibles(caja, tenant.consultorioId(), financiadorId,
				hoy.minusDays(1), hoy.plusDays(1), 50, 0)).isEmpty();

		PresentacionView borrador = presentacionService.crear(caja, tenant.consultorioId(),
				new PresentacionCommands.Alta(financiadorId, hoy.minusDays(1), hoy.plusDays(1), "ARS", List.of()));
		assertThatThrownBy(() -> presentacionService.agregarItem(
				caja, tenant.consultorioId(), borrador.id(), obligacionId))
				.isInstanceOf(ObligacionNoPresentableException.class)
				.hasMessageContaining("DEUDA_DEL_PACIENTE");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM presentacion_item WHERE obligacion_id = ?",
				Integer.class, obligacionId)).isZero();
	}

	// =================================================================================

	private Tenant tenantPorPack() {
		Tenant tenant = fx.crearTenant(10, 2);
		PaseVentaYConsumoIT.declararPorPack(jdbc, tenant.ofertaId());
		return tenant;
	}

	private ResultadoDeVenta vender(Tenant tenant, int persona) {
		long productoId = jdbc.query("""
				SELECT id FROM producto_servicio WHERE organization_id = ? AND active = 1 AND tipo = 'PACK'
				""", rs -> rs.next() ? rs.getLong(1) : 0L, tenant.organizationId());
		if (productoId == 0L) {
			productoId = productoService.crear(tenant.actor(), tenant.consultorioId(),
					PaseVentaYConsumoIT.comando(tenant.ofertaId(), "Pack", 8)).id();
		}
		return ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, tenant.persona(persona), null));
	}

	/** El ORG_ADMIN del tenant, visto por billing: tiene {@code cobro:register} y {@code caja:operate}. */
	private static OperatingActor actorDeBilling(Tenant tenant) {
		return new OperatingActor(tenant.actor().accountId(), false, tenant.organizationId(), tenant.consultorioId());
	}

	private CobroView cobrar(Tenant tenant, OperatingActor actor, int persona, long obligacionId, BigDecimal importe) {
		return cobroService.registrar(actor, tenant.consultorioId(), new CobroCommand(
				tenant.persona(persona), importe,
				List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, importe, null)),
				List.of(new CobroCommand.ImputacionPedida(obligacionId, importe)),
				null));
	}

	private ObligacionView unicaDeuda(Tenant tenant, OperatingActor actor, int persona) {
		List<ObligacionView> deudas = obligacionService.deLaPersona(
				actor, tenant.consultorioId(), tenant.persona(persona));
		assertThat(deudas).hasSize(1);
		return deudas.getFirst();
	}

	private void abrirCaja(Tenant tenant) {
		jdbc.update("""
				INSERT INTO jornada_caja (organization_id, consultorio_id, fecha_negocio, moneda,
				                          estado, saldo_inicial, saldo_arqueo, abierta_en,
				                          abierta_por_cuenta_id, created_at, updated_at)
				VALUES (?, ?, ?, 'ARS', 'ABIERTA', 0.00, 0.00, UTC_TIMESTAMP(6), ?,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", tenant.organizationId(), tenant.consultorioId(),
				LocalDate.now(ZoneId.of(ActividadItFixture.ZONA)).toString(), tenant.actor().accountId());
	}

	private long insertarFinanciador(long organizationId) {
		String codigo = "FIN-" + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'PREPAGA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, codigo, "Financiador " + codigo);
		return jdbc.queryForObject("SELECT id FROM financiador WHERE organization_id = ? AND codigo = ?",
				Long.class, organizationId, codigo);
	}

	private BigDecimal saldoDeCaja(Tenant tenant) {
		return jdbc.queryForObject("""
				SELECT saldo_arqueo FROM jornada_caja
				 WHERE organization_id = ? AND consultorio_id = ? AND estado = 'ABIERTA'
				""", BigDecimal.class, tenant.organizationId(), tenant.consultorioId());
	}

	private String estadoDeDeuda(long obligacionId) {
		return jdbc.queryForObject("SELECT estado FROM obligacion WHERE id = ?", String.class, obligacionId);
	}

	private BigDecimal saldoDeDeuda(long obligacionId) {
		return jdbc.queryForObject("SELECT saldo FROM obligacion WHERE id = ?", BigDecimal.class, obligacionId);
	}

	private long versionDeDeuda(long obligacionId) {
		return jdbc.queryForObject("SELECT version FROM obligacion WHERE id = ?", Long.class, obligacionId);
	}

	private String estadoDelPase(long paseId) {
		return jdbc.queryForObject("SELECT estado FROM pase_servicio WHERE id = ?", String.class, paseId);
	}

	private long version(long paseId) {
		return jdbc.queryForObject("SELECT version FROM pase_servicio WHERE id = ?", Long.class, paseId);
	}
}
