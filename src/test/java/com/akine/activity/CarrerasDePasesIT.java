package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Desenlace;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.CarreraDirigida.Retencion;
import com.akine.activity.application.AjustarSaldoCommand;
import com.akine.activity.application.AjusteDePaseService;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.ConsumoDeCredito.Efecto;
import com.akine.activity.application.CrearProductoCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.RenovacionDeAbonos;
import com.akine.activity.application.ResultadoDeAsistencia;
import com.akine.activity.application.ResultadoDeVenta;
import com.akine.activity.application.VencimientoDePases;
import com.akine.activity.application.VenderPaseCommand;
import com.akine.activity.application.VentaDePaseService;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.TipoProducto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Las carreras de pases y abonos que no tenian IT</b> (AKINE-09.03): la venta repetida con la
 * misma clave, y cada operacion que mueve un pase contra las otras —consumo, ajuste, anulacion,
 * vencimiento y renovacion—.
 *
 * <p>Como en {@link DevolucionYAjusteConcurrenteIT}, no se afirma quien gana —eso lo decide InnoDB—
 * sino que, gane quien gane, el desenlace es uno de los que una ejecucion en serie podria dar: el
 * ledger explica el saldo, ningun credito se usa en un pase anulado o vencido, y ninguna deuda queda
 * viva sin su pase (ni al reves).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class CarrerasDePasesIT {

	private static final int RONDAS = 3;
	private static final String BLOQUEAR_PRODUCTO = "%FROM producto_servicio%FOR UPDATE%";

	@Autowired private ProductoService productoService;
	@Autowired private VentaDePaseService ventaService;
	@Autowired private AjusteDePaseService ajusteService;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private AsistenciaService asistenciaService;
	@Autowired private VencimientoDePases vencimiento;
	@Autowired private RenovacionDeAbonos renovacion;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private DataSource dataSource;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	// =================================================================================
	// Venta con la misma Idempotency-Key
	// =================================================================================

	@Test
	@DisplayName("Dos ventas con la misma clave que ya pasaron el chequeo de idempotencia: la segunda devuelve la primera, no un 409")
	void venta_con_la_misma_clave_dirigida() {
		Tenant tenant = tenantPorPack();
		long producto = pack(tenant, 8);
		String clave = UUID.randomUUID().toString();
		Callable<ResultadoDeVenta> vender = () -> ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(producto, tenant.persona(0), clave));

		Desenlace<ResultadoDeVenta> primera;
		Desenlace<ResultadoDeVenta> segunda;
		try (CarreraDirigida carrera = new CarreraDirigida(jdbc);
				Retencion fila = Retencion.de(dataSource, "producto_servicio", producto)) {
			// Las dos leyeron "no hay venta con esta clave" y esperan el producto bloqueado.
			Future<Desenlace<ResultadoDeVenta>> a = carrera.largarHastaBloquearse(vender, BLOQUEAR_PRODUCTO);
			Future<Desenlace<ResultadoDeVenta>> b = carrera.largarHastaBloquearse(vender, BLOQUEAR_PRODUCTO);
			fila.soltar();
			primera = CarreraDirigida.resultado(a);
			segunda = CarreraDirigida.resultado(b);
		}

		afirmarUnaSolaVenta(tenant, clave, List.of(primera, segunda));
	}

	@Test
	@DisplayName("Rafaga de seis ventas con la misma clave: un pase, una deuda, una COMPRA, y todas devuelven el mismo pase")
	void venta_con_la_misma_clave_en_rafaga() {
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			Tenant tenant = tenantPorPack();
			long producto = pack(tenant, 8);
			String clave = UUID.randomUUID().toString();
			List<Callable<ResultadoDeVenta>> tareas = new ArrayList<>();
			for (int i = 0; i < 6; i++) {
				tareas.add(() -> ventaService.vender(tenant.actor(), tenant.consultorioId(),
						new VenderPaseCommand(producto, tenant.persona(0), clave)));
			}
			afirmarUnaSolaVenta(tenant, clave, ActividadItFixture.enParalelo(tareas));
		}
	}

	private void afirmarUnaSolaVenta(Tenant tenant, String clave, List<Desenlace<ResultadoDeVenta>> desenlaces) {
		assertThat(desenlaces).as("ninguna venta repetida falla: %s", desenlaces).noneMatch(Desenlace::fallo);
		long paseId = jdbc.queryForObject(
				"SELECT id FROM pase_servicio WHERE organization_id = ? AND idempotency_key = ?",
				Long.class, tenant.organizationId(), clave);
		assertThat(desenlaces).allSatisfy(d -> assertThat(d.valor().pase().id()).isEqualTo(paseId));
		assertThat(desenlaces.stream().filter(d -> d.valor().creada()).count())
				.as("exactamente una crea").isEqualTo(1);
		assertThat(contar("SELECT COUNT(*) FROM pase_servicio WHERE organization_id = ?", tenant.organizationId()))
				.isEqualTo(1);
		assertThat(contar("SELECT COUNT(*) FROM obligacion WHERE pase_id = ?", paseId)).isEqualTo(1);
		assertThat(contar("SELECT COUNT(*) FROM movimiento_pase WHERE pase_id = ?", paseId)).isEqualTo(1);
		assertThat(contar("SELECT COUNT(*) FROM audit_event WHERE event_type = 'PASE_VENDIDO' AND entity_id = ?",
				paseId)).isEqualTo(1);
	}

	// =================================================================================
	// Consumo, ajuste y anulacion
	// =================================================================================

	@Test
	@DisplayName("Consumo contra anulacion del mismo pase: o se anula sin uso y la asistencia queda sin pase, o se consume y la anulacion es 409")
	void consumo_contra_anulacion() {
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			Tenant tenant = tenantPorPack();
			long pase = vender(tenant, pack(tenant, 8), 0);
			long clase = claseEnCurso(tenant, 1);
			long inscripcion = fx.inscripcionDe(clase, tenant.persona(0));

			List<Desenlace<Object>> desenlaces = ActividadItFixture.enParalelo(List.of(
					() -> asistenciaService.registrar(tenant.actor(), tenant.consultorioId(), clase,
							new RegistrarAsistenciaCommand(inscripcion, ResultadoAsistencia.PRESENTE,
									OrigenAsistencia.MOSTRADOR, null, null)),
					() -> ajusteService.anular(tenant.actor(), tenant.consultorioId(), pase, "Error de venta", 0)));

			Desenlace<Object> asistencia = desenlaces.get(0);
			Desenlace<Object> anulacion = desenlaces.get(1);
			assertThat(asistencia.fallo()).as("la asistencia se registra siempre: %s", desenlaces).isFalse();
			Efecto efecto = ((ResultadoDeAsistencia) asistencia.valor()).consumo().efecto();
			if (anulacion.fallo()) {
				assertThat(ActividadItFixture.causaEs(anulacion.error(), OptimisticLockingFailureException.class))
						.as("%s", desenlaces).isTrue();
				assertThat(efecto).isEqualTo(Efecto.CONSUMIDO);
				assertThat(estado(pase)).isEqualTo("ACTIVO");
				assertThat(saldo(pase)).isEqualTo(7);
				assertThat(estadoDeLaDeuda(pase)).isEqualTo("PENDIENTE");
			} else {
				assertThat(efecto).as("un pase anulado no se consume").isEqualTo(Efecto.SIN_PASE_ELEGIBLE);
				assertThat(estado(pase)).isEqualTo("ANULADO");
				assertThat(saldo(pase)).isZero();
				assertThat(estadoDeLaDeuda(pase)).isEqualTo("ANULADA");
			}
			assertThat(sumaDelLedger(pase)).as("ronda %d", ronda).isEqualTo(saldo(pase));
		}
	}

	@Test
	@DisplayName("Ajuste contra anulacion con la misma version leida: uno gana, el otro es 409, y nunca se ajusta un pase anulado")
	void ajuste_contra_anulacion() {
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			Tenant tenant = tenantPorPack();
			long pase = vender(tenant, pack(tenant, 8), 0);

			List<Desenlace<Object>> desenlaces = ActividadItFixture.enParalelo(List.of(
					() -> ajusteService.ajustar(tenant.actor(), tenant.consultorioId(), pase,
							new AjustarSaldoCommand(-2, "Clases tomadas antes del sistema", 0), true),
					() -> ajusteService.anular(tenant.actor(), tenant.consultorioId(), pase, "Error de venta", 0)));

			Desenlace<Object> ajuste = desenlaces.get(0);
			Desenlace<Object> anulacion = desenlaces.get(1);
			assertThat(ajuste.fallo() ^ anulacion.fallo()).as("gana exactamente uno: %s", desenlaces).isTrue();
			Desenlace<Object> perdedor = ajuste.fallo() ? ajuste : anulacion;
			assertThat(ActividadItFixture.causaEs(perdedor.error(), OptimisticLockingFailureException.class))
					.as("%s", desenlaces).isTrue();
			if (anulacion.fallo()) {
				assertThat(estado(pase)).isEqualTo("ACTIVO");
				assertThat(saldo(pase)).isEqualTo(6);
				assertThat(estadoDeLaDeuda(pase)).isEqualTo("PENDIENTE");
			} else {
				assertThat(estado(pase)).isEqualTo("ANULADO");
				assertThat(saldo(pase)).isZero();
				assertThat(estadoDeLaDeuda(pase)).isEqualTo("ANULADA");
				assertThat(contar("SELECT COUNT(*) FROM movimiento_pase WHERE pase_id = ? AND tipo = 'AJUSTE' "
						+ "AND motivo NOT LIKE 'Anulacion:%'", pase)).as("ningun ajuste sobre el anulado").isZero();
			}
			assertThat(sumaDelLedger(pase)).as("ronda %d", ronda).isEqualTo(saldo(pase));
		}
	}

	// =================================================================================
	// Jobs contra el mostrador
	// =================================================================================

	@Test
	@DisplayName("Vencimiento contra el consumo de una clase del ultimo dia de vigencia: un solo VENCIMIENTO y el ledger cuadra en cero")
	void vencimiento_contra_consumo() {
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			Tenant tenant = tenantPorPack();
			long pase = vender(tenant, pack(tenant, 8), 0);
			long clase = claseEnCurso(tenant, 1 + ronda);
			long inscripcion = fx.inscripcionDe(clase, tenant.persona(0));
			// La clase fue hace dos dias, el ultimo dia de vigencia del pase: la asistencia se marca
			// tarde, mientras el job de vencimiento corre. Las fechas no se pueden esperar: se corren
			// como dato de prueba, igual que en PaseAbonoAjusteYVencimientoIT.
			jdbc.update("UPDATE clase_programada SET inicio = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 DAY), "
					+ "fin = DATE_ADD(DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 DAY), INTERVAL 1 HOUR) WHERE id = ?", clase);
			LocalDate diaDeLaClase = jdbc.queryForObject("SELECT inicio FROM clase_programada WHERE id = ?",
					java.sql.Timestamp.class, clase).toInstant().atZone(ZoneId.of(ActividadItFixture.ZONA)).toLocalDate();
			jdbc.update("UPDATE pase_servicio SET vigencia_desde = ?, vigencia_hasta = ? WHERE id = ?",
					diaDeLaClase.minusDays(30), diaDeLaClase, pase);

			List<Desenlace<Object>> desenlaces = ActividadItFixture.enParalelo(List.of(
					() -> asistenciaService.registrar(tenant.actor(), tenant.consultorioId(), clase,
							new RegistrarAsistenciaCommand(inscripcion, ResultadoAsistencia.PRESENTE,
									OrigenAsistencia.MOSTRADOR, null, null)),
					() -> vencimiento.vencerLosVencidos(500)));

			assertThat(desenlaces).as("%s", desenlaces).noneMatch(Desenlace::fallo);
			Efecto efecto = ((ResultadoDeAsistencia) desenlaces.get(0).valor()).consumo().efecto();
			assertThat(estado(pase)).isEqualTo("VENCIDO");
			assertThat(saldo(pase)).isZero();
			assertThat(sumaDelLedger(pase)).as("ronda %d, %s", ronda, efecto).isZero();
			assertThat(contar("SELECT COUNT(*) FROM movimiento_pase WHERE pase_id = ? AND tipo = 'VENCIMIENTO'", pase))
					.isEqualTo(1);
			int vencidos = jdbc.queryForObject(
					"SELECT cantidad FROM movimiento_pase WHERE pase_id = ? AND tipo = 'VENCIMIENTO'", Integer.class, pase);
			// Si el consumo llego primero, vence el remanente (7); si no, la asistencia queda sin pase
			// y vencen los 8. Nunca un consumo despues del vencimiento.
			assertThat(vencidos).isEqualTo(efecto == Efecto.CONSUMIDO ? -7 : -8);
			if (efecto != Efecto.CONSUMIDO) {
				assertThat(efecto).isEqualTo(Efecto.SIN_PASE_ELEGIBLE);
			}
		}
	}

	@Test
	@DisplayName("Renovacion automatica contra la anulacion manual del periodo: o se anula y no se renueva, o se renueva y la anulacion es 409")
	void renovacion_contra_anulacion() {
		for (int ronda = 0; ronda < RONDAS; ronda++) {
			Tenant tenant = fx.crearTenant(10, 1);
			jdbc.update("UPDATE oferta_servicio_consultorio SET esquema_cobro = 'POR_ABONO', momento_devengo = 'VENTA' "
					+ "WHERE id = ?", tenant.ofertaId());
			long producto = productoService.crear(tenant.actor(), tenant.consultorioId(),
					new CrearProductoCommand(tenant.ofertaId(), "Abono mensual", null, 8,
							new BigDecimal("30000.00"), "ARS", 31, LocalDate.now().minusDays(60), null,
							TipoProducto.ABONO)).id();
			LocalDate hoy = LocalDate.now(ZoneId.of(ActividadItFixture.ZONA));
			long pase = ventaService.vender(tenant.actor(), tenant.consultorioId(),
					new VenderPaseCommand(producto, tenant.persona(0), null, hoy.minusDays(20), null, true)).pase().id();
			jdbc.update("UPDATE pase_servicio SET vigencia_desde = ?, vigencia_hasta = ? WHERE id = ?",
					hoy.minusDays(29), hoy, pase);
			long version = version(pase);

			List<Desenlace<Object>> desenlaces = ActividadItFixture.enParalelo(List.of(
					() -> renovacion.renovarLosQueTerminan(0, 500),
					() -> ajusteService.anular(tenant.actor(), tenant.consultorioId(), pase, "Se dio de baja", version)));

			assertThat(desenlaces.get(0).fallo()).as("el job no falla: %s", desenlaces).isFalse();
			Desenlace<Object> anulacion = desenlaces.get(1);
			int renovaciones = contar("SELECT COUNT(*) FROM pase_servicio WHERE renueva_pase_id = ?", pase);
			if (anulacion.fallo()) {
				assertThat(ActividadItFixture.causaEs(anulacion.error(), OptimisticLockingFailureException.class))
						.as("%s", desenlaces).isTrue();
				assertThat(estado(pase)).isEqualTo("ACTIVO");
				assertThat(renovaciones).as("se renovo una vez").isEqualTo(1);
			} else {
				assertThat(estado(pase)).isEqualTo("ANULADO");
				assertThat(renovaciones).as("un abono anulado no se renueva").isZero();
				assertThat(estadoDeLaDeuda(pase)).isEqualTo("ANULADA");
			}
			// Una deuda por periodo, ni mas ni menos.
			assertThat(contar("SELECT COUNT(*) FROM obligacion WHERE organization_id = ?", tenant.organizationId()))
					.as("ronda %d", ronda).isEqualTo(1 + renovaciones);
		}
	}

	// =================================================================================

	private Tenant tenantPorPack() {
		Tenant tenant = fx.crearTenant(10, 1);
		PaseVentaYConsumoIT.declararPorPack(jdbc, tenant.ofertaId());
		return tenant;
	}

	private long pack(Tenant tenant, int creditos) {
		return productoService.crear(tenant.actor(), tenant.consultorioId(),
				PaseVentaYConsumoIT.comando(tenant.ofertaId(), "Pack", creditos)).id();
	}

	private long vender(Tenant tenant, long producto, int persona) {
		return ventaService.vender(tenant.actor(), tenant.consultorioId(),
				new VenderPaseCommand(producto, tenant.persona(persona), null)).pase().id();
	}

	private long claseEnCurso(Tenant tenant, int diasAdelante) {
		long clase = fx.programarClase(tenant, 10, diasAdelante);
		for (Long persona : tenant.personas()) {
			fx.inscribir(tenant, clase, persona, false);
		}
		asistenciaService.iniciar(tenant.actor(), tenant.consultorioId(), clase);
		return clase;
	}

	private String estado(long paseId) {
		return jdbc.queryForObject("SELECT estado FROM pase_servicio WHERE id = ?", String.class, paseId);
	}

	private String estadoDeLaDeuda(long paseId) {
		return jdbc.queryForObject("SELECT estado FROM obligacion WHERE pase_id = ?", String.class, paseId);
	}

	private int saldo(long paseId) {
		return jdbc.queryForObject("SELECT creditos_disponibles FROM pase_servicio WHERE id = ?", Integer.class, paseId);
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
