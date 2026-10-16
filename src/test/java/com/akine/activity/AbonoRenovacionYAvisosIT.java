package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.AjustarSaldoCommand;
import com.akine.activity.application.AjusteDePaseService;
import com.akine.activity.application.AvisosDePases;
import com.akine.activity.application.CrearProductoCommand;
import com.akine.activity.application.EditarProductoCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.MovimientoPaseView;
import com.akine.activity.application.PaseView;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.RenovacionDeAbonos;
import com.akine.activity.application.VenderPaseCommand;
import com.akine.activity.application.VentaDePaseService;
import com.akine.activity.domain.TipoProducto;
import com.akine.activity.domain.exception.PaseNoAjustableException;
import com.akine.activity.domain.exception.PaseNotAccessibleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AKINE-08.08 contra MySQL real: <b>renovacion automatica de abonos, avisos de vencimiento y saldo
 * bajo (RF-M26-008) y ledger paginado</b>. Lo que se verifica es lo que quedo en el motor: cuantos
 * periodos, cuantas obligaciones, cuantas filas del outbox, y que una segunda pasada —o dos a la
 * vez— no agrega ninguna.
 *
 * <p>Las fechas no se pueden esperar: la vigencia del periodo se corre como dato de prueba, igual
 * que en {@code PaseAbonoAjusteYVencimientoIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class AbonoRenovacionYAvisosIT {

	@Autowired private ProductoService productoService;
	@Autowired private VentaDePaseService ventaService;
	@Autowired private AjusteDePaseService ajusteService;
	@Autowired private RenovacionDeAbonos renovacion;
	@Autowired private AvisosDePases avisos;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@Test
	@DisplayName("Renovacion: el job vende el periodo siguiente una sola vez, con el precio de hoy y su obligacion; dos pasadas no renuevan dos veces")
	void renovacion_idempotente() {
		Tenant tenant = tenantPorAbono();
		long productoId = productoService.crear(tenant.actor(), tenant.consultorioId(), abono(tenant.ofertaId(), 31)).id();
		LocalDate hoy = hoy();
		long anterior = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, tenant.persona(0), null, hoy.minusDays(20), null, true)).pase().id();
		// Un abono sin renovacion pedida no se toca: es opt-in.
		long sinRenovacion = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, tenant.persona(1), null, hoy.minusDays(20), null)).pase().id();
		terminaEn(anterior, hoy.plusDays(1));
		terminaEn(sinRenovacion, hoy.plusDays(1));
		// Cambio de precio: vale para el periodo nuevo, no para el vendido.
		productoService.editar(tenant.actor(), tenant.consultorioId(), productoId, new EditarProductoCommand(
				null, null, null, new BigDecimal("36000.00"), null, null, null, null, false, 0));

		renovacion.renovarLosQueTerminan(3, 500);
		renovacion.renovarLosQueTerminan(3, 500);

		List<Map<String, Object>> renovaciones = jdbc.queryForList(
				"SELECT * FROM pase_servicio WHERE renueva_pase_id = ?", anterior);
		assertThat(renovaciones).hasSize(1);
		Map<String, Object> nuevo = renovaciones.get(0);
		long nuevoId = ((Number) nuevo.get("id")).longValue();
		assertThat(fechaDe(nuevoId, "vigencia_desde")).isEqualTo(hoy.plusDays(2));
		assertThat(fechaDe(nuevoId, "vigencia_hasta")).isEqualTo(hoy.plusDays(32));
		assertThat((BigDecimal) nuevo.get("snapshot_precio")).isEqualByComparingTo("36000.00");
		assertThat(nuevo.get("estado")).isEqualTo("ACTIVO");
		assertThat(((Number) nuevo.get("renovacion_automatica")).intValue()).isEqualTo(1);
		assertThat(((Number) nuevo.get("vendido_por_cuenta_id")).longValue()).isEqualTo(tenant.actor().accountId());
		// El periodo viejo no se vuelve a renovar: su marca se apago en la misma transaccion.
		assertThat(jdbc.queryForObject("SELECT renovacion_automatica FROM pase_servicio WHERE id = ?",
				Integer.class, anterior)).isZero();
		// Un periodo, una obligacion VENTA_ABONO, con el precio nuevo; el viejo conserva la suya.
		assertThat(jdbc.queryForList("SELECT origen FROM obligacion WHERE pase_id = ?", String.class, nuevoId))
				.containsExactly("VENTA_ABONO");
		assertThat(jdbc.queryForObject("SELECT importe_original FROM obligacion WHERE pase_id = ?",
				BigDecimal.class, anterior)).isEqualByComparingTo("30000.00");
		assertThat(jdbc.queryForList("SELECT tipo FROM movimiento_pase WHERE pase_id = ?", String.class, nuevoId))
				.containsExactly("COMPRA");
		assertThat(contar("SELECT COUNT(*) FROM pase_servicio WHERE renueva_pase_id = ?", sinRenovacion)).isZero();
		assertThat(contar("SELECT COUNT(*) FROM audit_event WHERE event_type = 'ABONO_RENOVADO' AND entity_id = ?",
				nuevoId)).isEqualTo(1);

		// Prender la renovacion de un periodo ya renovado es 409: se configura sobre el nuevo.
		long version = version(anterior);
		assertThatThrownBy(() -> ventaService.configurarRenovacion(
				tenant.actor(), tenant.consultorioId(), anterior, true, version))
				.isInstanceOfSatisfying(PaseNoAjustableException.class,
						e -> assertThat(e.getCausa()).isEqualTo(PaseNoAjustableException.Causa.YA_RENOVADO));
		// Apagarla en el nuevo deja la cadena quieta: la pasada siguiente no vende nada.
		PaseView apagado = ventaService.configurarRenovacion(
				tenant.actor(), tenant.consultorioId(), nuevoId, false, version(nuevoId));
		assertThat(apagado.renovacionAutomatica()).isFalse();
		assertThat(apagado.version()).isEqualTo(version(nuevoId));
		terminaEn(nuevoId, hoy);
		renovacion.renovarLosQueTerminan(3, 500);
		assertThat(contar("SELECT COUNT(*) FROM pase_servicio WHERE renueva_pase_id = ?", nuevoId)).isZero();
	}

	@Test
	@DisplayName("Renovacion: dos instancias del job a la vez sobre los mismos abonos renuevan cada uno exactamente una vez")
	void renovacion_concurrente() {
		Tenant tenant = tenantPorAbono();
		long productoId = productoService.crear(tenant.actor(), tenant.consultorioId(), abono(tenant.ofertaId(), 31)).id();
		LocalDate hoy = hoy();
		List<Long> abonos = new java.util.ArrayList<>();
		for (int i = 0; i < 3; i++) {
			long paseId = ventaService.vender(tenant.actor(), tenant.consultorioId(),
					new VenderPaseCommand(productoId, tenant.persona(i), null, hoy.minusDays(20), null, true)).pase().id();
			terminaEn(paseId, hoy);
			abonos.add(paseId);
		}

		List<Callable<Integer>> instancias = List.of(
				() -> renovacion.renovarLosQueTerminan(0, 500),
				() -> renovacion.renovarLosQueTerminan(0, 500),
				() -> renovacion.renovarLosQueTerminan(0, 500));
		List<Desenlace<Integer>> desenlaces = ActividadItFixture.enParalelo(instancias);

		assertThat(desenlaces).noneMatch(Desenlace::fallo);
		for (long paseId : abonos) {
			assertThat(contar("SELECT COUNT(*) FROM pase_servicio WHERE renueva_pase_id = ?", paseId))
					.as("renovaciones del abono %d", paseId).isEqualTo(1);
		}
		assertThat(contar("SELECT COUNT(*) FROM obligacion WHERE organization_id = ? AND origen = 'VENTA_ABONO'",
				tenant.organizationId())).isEqualTo(6);
	}

	@Test
	@DisplayName("Renovacion imposible: producto de baja o periodo ya vendido a mano se apagan con su motivo, sin venta ni deuda")
	void renovacion_omitida() {
		Tenant tenant = tenantPorAbono();
		long productoId = productoService.crear(tenant.actor(), tenant.consultorioId(), abono(tenant.ofertaId(), 31)).id();
		LocalDate hoy = hoy();
		long ocupado = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, tenant.persona(0), null, hoy.minusDays(20), null, true)).pase().id();
		terminaEn(ocupado, hoy);
		// El mostrador ya vendio el periodo siguiente: renovar seria cobrarlo dos veces.
		ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, tenant.persona(0), null, hoy.plusDays(1), null));

		renovacion.renovarLosQueTerminan(0, 500);

		Map<String, Object> fila = jdbc.queryForMap(
				"SELECT renovacion_automatica, renovacion_omitida_motivo FROM pase_servicio WHERE id = ?", ocupado);
		assertThat(((Number) fila.get("renovacion_automatica")).intValue()).isZero();
		assertThat((String) fila.get("renovacion_omitida_motivo")).contains("se superpone");
		assertThat(contar("SELECT COUNT(*) FROM pase_servicio WHERE renueva_pase_id = ?", ocupado)).isZero();

		long deBaja = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, tenant.persona(1), null, hoy.minusDays(20), null, true)).pase().id();
		terminaEn(deBaja, hoy);
		productoService.darDeBaja(tenant.actor(), tenant.consultorioId(), productoId, "Se discontinua");
		int obligaciones = contar("SELECT COUNT(*) FROM obligacion WHERE organization_id = ?", tenant.organizationId());

		renovacion.renovarLosQueTerminan(0, 500);

		assertThat(jdbc.queryForObject("SELECT renovacion_omitida_motivo FROM pase_servicio WHERE id = ?",
				String.class, deBaja)).contains("dado de baja");
		assertThat(contar("SELECT COUNT(*) FROM obligacion WHERE organization_id = ?", tenant.organizationId()))
				.isEqualTo(obligaciones);
		assertThat(contar("SELECT COUNT(*) FROM audit_event WHERE event_type = 'ABONO_RENOVACION_OMITIDA' "
				+ "AND organization_id = ?", tenant.organizationId())).isEqualTo(2);
	}

	@Test
	@DisplayName("RF-M26-008: un aviso de vencimiento y uno de saldo bajo por pase, por el outbox, sin tocar saldo ni estado; sin correo no se encola")
	void avisos_de_vencimiento_y_saldo_bajo() {
		Tenant tenant = fx.crearTenant(10, 2);
		jdbc.update("UPDATE oferta_servicio_consultorio SET esquema_cobro = 'POR_PACK', momento_devengo = 'VENTA' "
				+ "WHERE id = ?", tenant.ofertaId());
		long productoId = productoService.crear(tenant.actor(), tenant.consultorioId(),
				PaseVentaYConsumoIT.comando(tenant.ofertaId(), "Pack", 4)).id();
		String email = "titular-" + tenant.organizationId() + "@ejemplo.test";
		jdbc.update("UPDATE persona SET email = ? WHERE id = ?", email, tenant.persona(0));
		long conCorreo = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, tenant.persona(0), null)).pase().id();
		long sinCorreo = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, tenant.persona(1), null)).pase().id();
		jdbc.update("UPDATE pase_servicio SET vigencia_hasta = ? WHERE id IN (?, ?)", hoy().plusDays(2), conCorreo, sinCorreo);
		ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), conCorreo,
				new AjustarSaldoCommand(-3, "Tres clases antes del sistema", version(conCorreo)));

		avisos.avisarVencimientos(3, 500);
		avisos.avisarVencimientos(3, 500);
		avisos.avisarSaldosBajos(1, 500);
		avisos.avisarSaldosBajos(1, 500);

		List<Map<String, Object>> filas = jdbc.queryForList(
				"SELECT tipo, destinatario, clave_idempotente FROM notification_outbox WHERE organization_id = ? ORDER BY id",
				tenant.organizationId());
		assertThat(filas).extracting(f -> f.get("tipo"), f -> f.get("clave_idempotente"))
				.containsExactly(
						org.assertj.core.groups.Tuple.tuple("PASE_POR_VENCER", "pase-por-vencer:" + conCorreo),
						org.assertj.core.groups.Tuple.tuple("PASE_SALDO_BAJO", "pase-saldo-bajo:" + conCorreo));
		assertThat(filas).allSatisfy(f -> assertThat(f.get("destinatario")).isEqualTo(email));
		// CA-M26-008-06: avisar no consume ni cambia el estado.
		assertThat(jdbc.queryForMap("SELECT creditos_disponibles, estado FROM pase_servicio WHERE id = ?", conCorreo))
				.containsEntry("creditos_disponibles", 1).containsEntry("estado", "ACTIVO");
		// Sin correo: resuelto (no se vuelve a mirar cada hora) y sin fila en el outbox.
		assertThat(jdbc.queryForObject("SELECT aviso_vencimiento_en FROM pase_servicio WHERE id = ?",
				Object.class, sinCorreo)).isNotNull();
		assertThat(contar("SELECT COUNT(*) FROM audit_event WHERE event_type = 'PASE_AVISO_DE_VENCIMIENTO' "
				+ "AND organization_id = ?", tenant.organizationId())).isEqualTo(2);
	}

	@Test
	@DisplayName("RF-M29-009: el ledger se pagina del mas viejo al mas nuevo y un pase de otro tenant es 404")
	void ledger_paginado() {
		Tenant tenant = fx.crearTenant(10, 1);
		jdbc.update("UPDATE oferta_servicio_consultorio SET esquema_cobro = 'POR_PACK', momento_devengo = 'VENTA' "
				+ "WHERE id = ?", tenant.ofertaId());
		long productoId = productoService.crear(tenant.actor(), tenant.consultorioId(),
				PaseVentaYConsumoIT.comando(tenant.ofertaId(), "Pack", 8)).id();
		long paseId = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(productoId, tenant.persona(0), null)).pase().id();
		ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), paseId, new AjustarSaldoCommand(-1, "uno", 0));
		ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), paseId,
				new AjustarSaldoCommand(-2, "dos", version(paseId)));

		Page<MovimientoPaseView> primera = ventaService.movimientos(tenant.actor(), tenant.consultorioId(), paseId, 0, 2);
		Page<MovimientoPaseView> segunda = ventaService.movimientos(tenant.actor(), tenant.consultorioId(), paseId, 1, 2);

		assertThat(primera.getTotalElements()).isEqualTo(3);
		assertThat(primera.getTotalPages()).isEqualTo(2);
		assertThat(primera.getContent()).extracting(MovimientoPaseView::tipo).containsExactly("COMPRA", "AJUSTE");
		assertThat(segunda.getContent()).extracting(MovimientoPaseView::saldoResultante).containsExactly(5);

		Tenant otro = fx.crearTenant(10, 1);
		assertThatThrownBy(() -> ventaService.movimientos(otro.actor(), otro.consultorioId(), paseId, 0, 20))
				.isInstanceOf(PaseNotAccessibleException.class);
	}

	// =================================================================================

	private Tenant tenantPorAbono() {
		Tenant tenant = fx.crearTenant(10, 3);
		jdbc.update("UPDATE oferta_servicio_consultorio SET esquema_cobro = 'POR_ABONO', momento_devengo = 'VENTA' "
				+ "WHERE id = ?", tenant.ofertaId());
		return tenant;
	}

	private static CrearProductoCommand abono(long ofertaId, int dias) {
		return new CrearProductoCommand(ofertaId, "Abono mensual", null, 8,
				new BigDecimal("30000.00"), "ARS", dias, LocalDate.now().minusDays(60), null,
				TipoProducto.ABONO);
	}

	/** Corre el fin del periodo (y el inicio, para que no dure mas que el producto). */
	private void terminaEn(long paseId, LocalDate fin) {
		jdbc.update("UPDATE pase_servicio SET vigencia_desde = ?, vigencia_hasta = ? WHERE id = ?",
				fin.minusDays(29), fin, paseId);
	}

	private static LocalDate hoy() {
		return LocalDate.now(ZoneId.of(ActividadItFixture.ZONA));
	}

	private LocalDate fechaDe(long paseId, String columna) {
		return jdbc.queryForObject("SELECT " + columna + " FROM pase_servicio WHERE id = ?", LocalDate.class, paseId);
	}

	private long version(long paseId) {
		return jdbc.queryForObject("SELECT version FROM pase_servicio WHERE id = ?", Long.class, paseId);
	}

	private int contar(String sql, Object arg) {
		return jdbc.queryForObject(sql, Integer.class, arg);
	}
}
