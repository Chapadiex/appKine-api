package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.ConsumoDeCredito.Efecto;
import com.akine.activity.application.CrearProductoCommand;
import com.akine.activity.application.EditarProductoCommand;
import com.akine.activity.application.IdempotencyKeyConflictException;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.PaseDetalleView;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.ProductoView;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.ResultadoDeAsistencia;
import com.akine.activity.application.ResultadoDeVenta;
import com.akine.activity.application.VenderPaseCommand;
import com.akine.activity.application.VentaDePaseService;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.exception.OfertaNoAdmitePackException;
import com.akine.activity.domain.exception.ProductoNoVendibleException;
import com.akine.activity.domain.exception.ProductoNotAccessibleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AKINE-08.06/08.07 contra MySQL real: <b>producto → venta → asistencia → consumo → saldo</b>, con
 * el reintento de la venta, la correccion de la asistencia y el invariante del ledger.
 *
 * <p>Todo se compara contra lo que quedo en el motor, no contra lo que dijo el servicio: un 201 no
 * prueba que la deuda quedo bien guardada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class PaseVentaYConsumoIT {

	@Autowired private ProductoService productoService;
	@Autowired private VentaDePaseService ventaService;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private AsistenciaService asistenciaService;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	@Test
	@DisplayName("Vender un pack devenga una obligacion sin cobrar, y el reintento no duplica nada")
	void venta_devenga_una_obligacion_e_idempotente() {
		Tenant tenant = tenantPorPack();
		ProductoView producto = crearPack(tenant, 8);
		String clave = "venta-" + UUID.randomUUID();

		ResultadoDeVenta venta = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(producto.id(), tenant.persona(0), clave));
		ResultadoDeVenta reintento = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(producto.id(), tenant.persona(0), clave));

		assertThat(venta.creada()).isTrue();
		assertThat(reintento.creada()).isFalse();
		assertThat(reintento.pase().id()).isEqualTo(venta.pase().id());
		assertThat(reintento.deuda().obligacionId()).isEqualTo(venta.deuda().obligacionId());

		long paseId = venta.pase().id();
		assertThat(contar("SELECT COUNT(*) FROM pase_servicio WHERE organization_id = ?", tenant.organizationId()))
				.isEqualTo(1);
		Map<String, Object> obligacion = jdbc.queryForMap("""
				SELECT origen, pase_id, sesion_id, responsable, concepto, estado, importe_original, saldo
				  FROM obligacion WHERE pase_id = ?
				""", paseId);
		assertThat(obligacion.get("origen")).isEqualTo("VENTA_PASE");
		assertThat(obligacion.get("sesion_id")).isNull();
		assertThat(obligacion.get("responsable")).isEqualTo("PACIENTE");
		assertThat(obligacion.get("concepto")).isEqualTo("PARTICULAR");
		assertThat(obligacion.get("estado")).isEqualTo("PENDIENTE");
		assertThat((BigDecimal) obligacion.get("importe_original")).isEqualByComparingTo("40000.00");
		assertThat(contar("SELECT COUNT(*) FROM obligacion WHERE pase_id = ?", paseId)).isEqualTo(1);
		// Vender no es cobrar: ni un cobro ni un movimiento de caja.
		assertThat(contar("SELECT COUNT(*) FROM cobro WHERE organization_id = ?", tenant.organizationId()))
				.isZero();

		Map<String, Object> pase = jdbc.queryForMap(
				"SELECT creditos_iniciales, creditos_disponibles, estado, vigencia_desde, vigencia_hasta "
						+ "FROM pase_servicio WHERE id = ?", paseId);
		assertThat(pase.get("creditos_disponibles")).isEqualTo(8);
		assertThat(pase.get("estado")).isEqualTo("ACTIVO");
		LocalDate desde = ((java.sql.Date) pase.get("vigencia_desde")).toLocalDate();
		LocalDate hasta = ((java.sql.Date) pase.get("vigencia_hasta")).toLocalDate();
		assertThat(hasta).isEqualTo(desde.plusDays(59));

		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM movimiento_pase WHERE pase_id = ? AND tipo = 'COMPRA' AND cantidad = 8",
				Integer.class, paseId)).isEqualTo(1);

		assertThatThrownBy(() -> ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(producto.id(), tenant.persona(1), clave)))
				.isInstanceOf(IdempotencyKeyConflictException.class);
	}

	@Test
	@DisplayName("Asistencia presente consume 8 -> 7 exactamente una vez; la correccion lo devuelve y lo vuelve a tomar")
	void asistencia_consume_y_la_correccion_devuelve() {
		Tenant tenant = tenantPorPack();
		ProductoView producto = crearPack(tenant, 8);
		long paseId = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(producto.id(), tenant.persona(0), null)).pase().id();
		long clase = claseEnCurso(tenant);
		long inscripcion = fx.inscripcionDe(clase, tenant.persona(0));

		ResultadoDeAsistencia presente = registrar(tenant, clase, inscripcion, ResultadoAsistencia.PRESENTE, null);
		assertThat(presente.consumo().efecto()).isEqualTo(Efecto.CONSUMIDO);
		assertThat(presente.consumo().saldo()).isEqualTo(7);
		assertThat(saldo(paseId)).isEqualTo(7);

		// CA-M29-003-06: el reintento no descuenta otra vez.
		ResultadoDeAsistencia repetido = registrar(tenant, clase, inscripcion, ResultadoAsistencia.PRESENTE, null);
		assertThat(repetido.sinCambios()).isTrue();
		assertThat(saldo(paseId)).isEqualTo(7);

		// Tarde sigue siendo "estuvo": corrige el hecho pero no toca el credito.
		ResultadoDeAsistencia tarde = registrar(tenant, clase, inscripcion,
				ResultadoAsistencia.PRESENTE_TARDE, "Llego a los veinte minutos");
		assertThat(tarde.consumo().efecto()).isEqualTo(Efecto.SIN_CAMBIO);
		assertThat(saldo(paseId)).isEqualTo(7);

		ResultadoDeAsistencia ausente = registrar(tenant, clase, inscripcion,
				ResultadoAsistencia.AUSENTE, "Se marco por error");
		assertThat(ausente.consumo().efecto()).isEqualTo(Efecto.DEVUELTO);
		assertThat(saldo(paseId)).isEqualTo(8);

		ResultadoDeAsistencia otraVez = registrar(tenant, clase, inscripcion,
				ResultadoAsistencia.PRESENTE, "Si vino, era otra lista");
		assertThat(otraVez.consumo().efecto()).isEqualTo(Efecto.CONSUMIDO);
		assertThat(saldo(paseId)).isEqualTo(7);

		// El ledger explica el saldo: COMPRA +8, CONSUMO -1, DEVOLUCION +1, CONSUMO -1.
		assertThat(jdbc.queryForList(
				"SELECT tipo FROM movimiento_pase WHERE pase_id = ? ORDER BY id", String.class, paseId))
				.containsExactly("COMPRA", "CONSUMO", "DEVOLUCION", "CONSUMO");
		assertThat(sumaDelLedger(paseId)).isEqualTo(saldo(paseId));
		// Un credito no es plata: ninguna obligacion nueva por la clase.
		assertThat(contar("SELECT COUNT(*) FROM obligacion WHERE organization_id = ?", tenant.organizationId()))
				.isEqualTo(1);

		PaseDetalleView detalle = ventaService.find(tenant.actor(), tenant.consultorioId(), paseId);
		assertThat(detalle.pase().creditosDisponibles()).isEqualTo(7);
		assertThat(detalle.movimientos()).hasSize(4);
	}

	@Test
	@DisplayName("Sin pase elegible la asistencia se registra igual y no mueve nada; el ultimo credito agota el pase")
	void sin_pase_elegible_y_agotado() {
		Tenant tenant = tenantPorPack();
		ProductoView producto = crearPack(tenant, 1);
		long paseId = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(producto.id(), tenant.persona(0), null)).pase().id();
		long clase = claseEnCurso(tenant);

		ResultadoDeAsistencia conPase = registrar(tenant, clase,
				fx.inscripcionDe(clase, tenant.persona(0)), ResultadoAsistencia.PRESENTE, null);
		assertThat(conPase.consumo().efecto()).isEqualTo(Efecto.CONSUMIDO);
		assertThat(jdbc.queryForObject("SELECT estado FROM pase_servicio WHERE id = ?", String.class, paseId))
				.isEqualTo("AGOTADO");

		ResultadoDeAsistencia sinPase = registrar(tenant, clase,
				fx.inscripcionDe(clase, tenant.persona(1)), ResultadoAsistencia.PRESENTE, null);
		assertThat(sinPase.registrada()).isTrue();
		assertThat(sinPase.consumo().efecto()).isEqualTo(Efecto.SIN_PASE_ELEGIBLE);
		assertThat(saldo(paseId)).isZero();

		// Devolver el ultimo reactiva el pase.
		registrar(tenant, clase, fx.inscripcionDe(clase, tenant.persona(0)), ResultadoAsistencia.AUSENTE, "Error");
		assertThat(jdbc.queryForObject("SELECT estado FROM pase_servicio WHERE id = ?", String.class, paseId))
				.isEqualTo("ACTIVO");
	}

	@Test
	@DisplayName("Producto: baja que no cascadea, edicion que no toca lo vendido, oferta que no cobra por pack")
	void reglas_del_producto() {
		Tenant tenant = tenantPorPack();
		ProductoView producto = crearPack(tenant, 8);
		long paseId = ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(producto.id(), tenant.persona(0), null)).pase().id();

		productoService.editar(tenant.actor(), tenant.consultorioId(), producto.id(),
				new EditarProductoCommand(null, null, null, new BigDecimal("55000.00"), null, null,
						null, null, false, producto.version()));
		assertThat((BigDecimal) jdbc.queryForObject(
				"SELECT snapshot_precio FROM pase_servicio WHERE id = ?", BigDecimal.class, paseId))
				.isEqualByComparingTo("40000.00");

		productoService.darDeBaja(tenant.actor(), tenant.consultorioId(), producto.id(), "Se discontinua");
		assertThat(jdbc.queryForObject("SELECT estado FROM pase_servicio WHERE id = ?", String.class, paseId))
				.isEqualTo("ACTIVO");
		assertThatThrownBy(() -> ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(producto.id(), tenant.persona(1), null)))
				.isInstanceOf(ProductoNoVendibleException.class);

		// El nombre de un producto dado de baja queda libre: deleted_key lo saca del unique.
		assertThat(crearPack(tenant, 8).nombre()).isEqualTo(producto.nombre());

		Tenant otro = fx.crearTenant(10, 1);
		assertThatThrownBy(() -> productoService.crear(otro.actor(), otro.consultorioId(),
				comando(otro.ofertaId(), "Pack sin politica", 8)))
				.isInstanceOf(OfertaNoAdmitePackException.class);
		// Cross-tenant: el producto de otro centro no existe.
		assertThatThrownBy(() -> ventaService.vender(otro.actor(), otro.consultorioId(),
				new VenderPaseCommand(producto.id(), otro.persona(0), null)))
				.isInstanceOf(ProductoNotAccessibleException.class);
	}

	// =================================================================================

	private Tenant tenantPorPack() {
		Tenant tenant = fx.crearTenant(10, 3);
		declararPorPack(jdbc, tenant.ofertaId());
		return tenant;
	}

	static void declararPorPack(JdbcTemplate jdbc, long ofertaId) {
		jdbc.update("UPDATE oferta_servicio_consultorio SET esquema_cobro = 'POR_PACK', "
				+ "momento_devengo = 'VENTA' WHERE id = ?", ofertaId);
	}

	private ProductoView crearPack(Tenant tenant, int creditos) {
		return productoService.crear(tenant.actor(), tenant.consultorioId(),
				comando(tenant.ofertaId(), "Pack Pilates", creditos));
	}

	static CrearProductoCommand comando(long ofertaId, String nombre, int creditos) {
		return new CrearProductoCommand(ofertaId, nombre, null, creditos, new BigDecimal("40000.00"),
				"ARS", 60, LocalDate.now().minusDays(10), null);
	}

	/** Una clase de manana con todas las personas del tenant inscriptas, ya iniciada. */
	private long claseEnCurso(Tenant tenant) {
		long clase = fx.programarClase(tenant, 10, 1);
		for (Long persona : tenant.personas()) {
			fx.inscribir(tenant, clase, persona, false);
		}
		asistenciaService.iniciar(tenant.actor(), tenant.consultorioId(), clase);
		return clase;
	}

	private ResultadoDeAsistencia registrar(
			Tenant tenant, long clase, long inscripcion, ResultadoAsistencia resultado, String motivo) {
		return asistenciaService.registrar(tenant.actor(), tenant.consultorioId(), clase,
				new RegistrarAsistenciaCommand(inscripcion, resultado, OrigenAsistencia.MOSTRADOR, null, motivo));
	}

	private int saldo(long paseId) {
		return jdbc.queryForObject("SELECT creditos_disponibles FROM pase_servicio WHERE id = ?", Integer.class, paseId);
	}

	private int sumaDelLedger(long paseId) {
		return jdbc.queryForObject("SELECT SUM(cantidad) FROM movimiento_pase WHERE pase_id = ?", Integer.class, paseId);
	}

	private int contar(String sql, Object arg) {
		return jdbc.queryForObject(sql, Integer.class, arg);
	}
}
