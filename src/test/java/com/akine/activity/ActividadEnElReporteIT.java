package com.akine.activity;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.AjustarSaldoCommand;
import com.akine.activity.application.AjusteDePaseService;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.CrearProductoCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.RenovacionDeAbonos;
import com.akine.activity.application.VencimientoDePases;
import com.akine.activity.application.VenderPaseCommand;
import com.akine.activity.application.VentaDePaseService;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.TipoProducto;
import com.akine.reporting.application.ExportadorCsv;
import com.akine.reporting.application.OperatingActor;
import com.akine.reporting.application.ReporteService;
import com.akine.reporting.application.ReporteView;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.FilaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
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
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AKINE-09.01 contra MySQL real: los reportes {@code CLASES} (RF-M23-007) y {@code PASES}
 * (RF-M23-010).
 *
 * <p><b>Los datos se siembran por los servicios de verdad</b> —inscripcion, cancelacion con
 * promocion, asistencia con correccion, cierre, venta, ajuste, anulacion, vencimiento y renovacion—
 * y no por INSERT: lo que el reporte tiene que contar es lo que esos servicios escriben, con sus
 * estados reales. Solo la clase (como en el resto de los ITs de {@code activity}) y el corrimiento
 * de una vigencia, que no se puede esperar, van por JDBC.
 *
 * <p>Cada indicador se compara contra el numero que el escenario tiene que producir, escrito a
 * mano. Un reporte que da "algo razonable" no prueba nada.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ActividadEnElReporteIT {

	@Autowired private ReporteService reportes;
	@Autowired private ExportadorCsv exportador;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private AsistenciaService asistenciaService;
	@Autowired private ClaseService claseService;
	@Autowired private ProductoService productoService;
	@Autowired private VentaDePaseService ventaService;
	@Autowired private AjusteDePaseService ajusteService;
	@Autowired private VencimientoDePases vencimiento;
	@Autowired private RenovacionDeAbonos renovacion;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	// =================================================================================
	// RF-M23-007 — clases, inscripciones, espera, asistencia
	// =================================================================================

	@Test
	@DisplayName("CLASES: programadas, dictadas, canceladas, ocupacion sobre capacidad efectiva, espera y asistencia corregida")
	void reporte_de_clases() {
		Tenant t = fx.crearTenant(10, 6);

		// Clase A, capacidad 3: tres con lugar, dos en espera. Se cancela una y sube la primera de
		// la cola. Se toma lista con una correccion y el cierre resuelve al que nadie marco.
		long claseA = fx.programarClase(t, 3, 1);
		for (int i = 0; i < 5; i++) {
			fx.inscribir(t, claseA, t.persona(i), true);
		}
		inscripcionService.cancelar(actorDe(t), t.consultorioId(), claseA,
				fx.inscripcionDe(claseA, t.persona(2)), "No puede venir");
		asistenciaService.iniciar(actorDe(t), t.consultorioId(), claseA);
		registrar(t, claseA, t.persona(0), ResultadoAsistencia.PRESENTE, null);
		registrar(t, claseA, t.persona(1), ResultadoAsistencia.AUSENTE, null);
		registrar(t, claseA, t.persona(1), ResultadoAsistencia.PRESENTE_TARDE, "Llego tarde, se marco mal");
		asistenciaService.cerrar(actorDe(t), t.consultorioId(), claseA);

		// Clase B, cancelada sin inscriptos.
		long claseB = fx.programarClase(t, 4, 2);
		claseService.cancelar(actorDe(t), t.consultorioId(), claseB, "Feriado");

		// Clase C, capacidad PROPIA 12 sobre una oferta de 10: la efectiva es 10 (RN-M23-007).
		long claseC = fx.programarClase(t, 12, 3);
		fx.inscribir(t, claseC, t.persona(5), false);
		fx.inscribir(t, claseC, t.persona(0), false);
		inscripcionService.confirmar(actorDe(t), t.consultorioId(), claseC,
				fx.inscripcionDe(claseC, t.persona(5)));

		// Fuera del periodo: no cuenta.
		long lejana = fx.programarClase(t, 5, 40);
		fx.inscribir(t, lejana, t.persona(4), false);

		// Otro tenant con una clase en el mismo periodo: no cuenta.
		Tenant otro = fx.crearTenant(10, 1);
		fx.inscribir(otro, fx.programarClase(otro, 5, 1), otro.persona(0), false);

		LocalDate hoy = hoy();
		ReporteView vista = reportes.generar(reporteDe(t), t.consultorioId(), ReporteCode.CLASES,
				hoy.minusDays(1), hoy.plusDays(10));

		assertThat(vista.omitidas()).isEmpty();
		assertThat(valor(vista, "clases", "clases-programadas")).isEqualByComparingTo("3");
		assertThat(valor(vista, "clases", "clases-dictadas")).isEqualByComparingTo("1");
		assertThat(valor(vista, "clases", "clases-en-curso")).isEqualByComparingTo("0");
		assertThat(valor(vista, "clases", "clases-por-dictar")).isEqualByComparingTo("1");
		assertThat(valor(vista, "clases", "clases-canceladas")).isEqualByComparingTo("1");
		// A: min(3, 10) = 3. C: min(12, 10) = 10. B cancelada no ofrece.
		assertThat(valor(vista, "clases", "clases-capacidad-ofrecida")).isEqualByComparingTo("13");
		// A: 3 lugares (el de la cancelada lo tomo la promovida). C: 2. La espera no ocupa.
		assertThat(valor(vista, "clases", "clases-lugares-ocupados")).isEqualByComparingTo("5");
		assertThat(valor(vista, "clases", "clases-ocupacion")).isEqualByComparingTo("38.46");

		AporteDeReporte clases = seccion(vista, "clases");
		assertThat(clases.filas()).hasSize(3);
		FilaDeReporte filaA = clases.filas().get(0);
		assertThat(filaA.celdas()).containsExactly(
				filaA.celdas().get(0), filaA.celdas().get(1),
				"Clase sintetica", "-", "REALIZADA", "3", "3", "100.00");
		assertThat(clases.filas().get(1).celdas()).contains("CANCELADA");
		assertThat(clases.filas().get(2).celdas().subList(4, 8))
				.containsExactly("PROGRAMADA", "10", "2", "20.00");

		// Inscripciones de A: p0 y p1 ASISTIO, p2 CANCELADA, p3 promovida y AUSENTE por el
		// cierre, p4 cancelada por el cierre (la cola no sobrevive a la clase). De C: una
		// CONFIRMADA y una RESERVADA.
		assertThat(valor(vista, "participacion", "participacion-inscripciones")).isEqualByComparingTo("7");
		assertThat(valor(vista, "participacion", "participacion-inscriptos")).isEqualByComparingTo("5");
		assertThat(valor(vista, "participacion", "participacion-confirmadas")).isEqualByComparingTo("1");
		assertThat(valor(vista, "participacion", "participacion-canceladas")).isEqualByComparingTo("2");
		assertThat(valor(vista, "participacion", "participacion-en-espera")).isEqualByComparingTo("0");
		assertThat(valor(vista, "participacion", "participacion-promovidas")).isEqualByComparingTo("1");
		assertThat(valor(vista, "participacion", "participacion-asistencias")).isEqualByComparingTo("3");
		assertThat(valor(vista, "participacion", "participacion-presentes")).isEqualByComparingTo("1");
		assertThat(valor(vista, "participacion", "participacion-presentes-tarde")).isEqualByComparingTo("1");
		assertThat(valor(vista, "participacion", "participacion-ausentes")).isEqualByComparingTo("1");
		assertThat(valor(vista, "participacion", "participacion-asistencias-corregidas"))
				.isEqualByComparingTo("1");
		assertThat(valor(vista, "participacion", "participacion-tasa-asistencia"))
				.isEqualByComparingTo("66.67");

		// La espera que todavia espera: una clase futura con la cola viva.
		long claseD = fx.programarClase(t, 2, 4);
		fx.inscribir(t, claseD, t.persona(0), true);
		fx.inscribir(t, claseD, t.persona(1), true);
		fx.inscribir(t, claseD, t.persona(2), true);
		ReporteView conEspera = reportes.generar(reporteDe(t), t.consultorioId(), ReporteCode.CLASES,
				hoy.minusDays(1), hoy.plusDays(10));
		assertThat(valor(conEspera, "participacion", "participacion-en-espera")).isEqualByComparingTo("1");
		assertThat(valor(conEspera, "clases", "clases-lugares-ocupados")).isEqualByComparingTo("7");
		assertThat(valor(conEspera, "clases", "clases-capacidad-ofrecida")).isEqualByComparingTo("15");

		// RF-M23-006: el CSV es la misma computacion, con fuente y criterio de cada numero.
		String csv = exportador.aCsv(conEspera);
		assertThat(csv).contains("\"Reporte\",\"CLASES\"")
				.contains("\"Seccion\",\"Clases\"")
				.contains("\"Seccion\",\"Inscripciones y asistencia\"")
				.contains("\"Lugares ocupados\",\"7\",\"CONTEO\",\"\",\"M28 clase_programada (cupo_ocupado)\"")
				.contains("\"En lista de espera\",\"1\",\"CONTEO\",\"\",\"M28 inscripcion_clase\"");
	}

	@Test
	@DisplayName("CLASES limitado: el PROFESIONAL ve solo sus clases; PASES le omite la seccion por pase:read")
	void actividad_propia_del_profesional() {
		Tenant t = fx.crearTenant(10, 2);
		long suya = fx.programarClase(t, 5, 1);
		long ajena = fx.programarClase(t, 5, 2);
		fx.inscribir(t, suya, t.persona(0), false);
		fx.inscribir(t, ajena, t.persona(0), false);
		fx.inscribir(t, ajena, t.persona(1), false);

		long cuenta = cuenta();
		long membership = membership(t, cuenta, "PROFESIONAL");
		jdbc.update("UPDATE clase_programada SET profesional_membership_id = ? WHERE id = ?", membership, suya);
		OperatingActor profesional = new OperatingActor(cuenta, false, t.organizationId(), t.consultorioId());

		LocalDate hoy = hoy();
		ReporteView vista = reportes.generar(profesional, t.consultorioId(), ReporteCode.CLASES,
				hoy.minusDays(1), hoy.plusDays(5));
		assertThat(valor(vista, "clases", "clases-programadas")).isEqualByComparingTo("1");
		assertThat(valor(vista, "participacion", "participacion-inscripciones")).isEqualByComparingTo("1");
		assertThat(vista.advertencias()).extracting(a -> a.codigo()).contains("alcance-actividad-propia");

		ReporteView pases = reportes.generar(profesional, t.consultorioId(), ReporteCode.PASES,
				hoy.minusDays(1), hoy.plusDays(5));
		assertThat(pases.secciones()).isEmpty();
		assertThat(pases.omitidas()).extracting(ReporteView.SeccionOmitida::seccion).containsExactly("pases");
	}

	// =================================================================================
	// RF-M23-010 — pases (packs)
	// =================================================================================

	@Test
	@DisplayName("PASES packs: vendidos, anulados, vencidos, consumos con devolucion, ajustes, vigentes y conciliacion con el ledger")
	void reporte_de_packs() {
		Tenant t = fx.crearTenant(10, 3);
		declarar(t.ofertaId(), "POR_PACK");
		long producto = productoService.crear(actorDe(t), t.consultorioId(),
				PaseVentaYConsumoIT.comando(t.ofertaId(), "Pack Pilates 8", 8)).id();

		long pack1 = vender(t, producto, 0);
		long pack2 = vender(t, producto, 1);
		long pack3 = vender(t, producto, 2);
		long pack4 = vender(t, producto, 2);

		ajusteService.anular(actorDe(t), t.consultorioId(), pack3, "Se vendio a la persona equivocada", 0);
		ajusteService.ajustar(actorDe(t), t.consultorioId(), pack2,
				new AjustarSaldoCommand(-2, "Dos clases antes del sistema", 0), true);

		long clase = fx.programarClase(t, 10, 1);
		fx.inscribir(t, clase, t.persona(0), false);
		fx.inscribir(t, clase, t.persona(1), false);
		asistenciaService.iniciar(actorDe(t), t.consultorioId(), clase);
		registrar(t, clase, t.persona(0), ResultadoAsistencia.PRESENTE, null);
		registrar(t, clase, t.persona(1), ResultadoAsistencia.PRESENTE, null);
		registrar(t, clase, t.persona(1), ResultadoAsistencia.AUSENTE, "No vino");

		LocalDate hoy = hoy();
		jdbc.update("UPDATE pase_servicio SET vigencia_desde = ?, vigencia_hasta = ? WHERE id = ?",
				hoy.minusDays(60), hoy.minusDays(2), pack4);
		vencimiento.vencerLosVencidos(500);

		ReporteView vista = reportes.generar(reporteDe(t), t.consultorioId(), ReporteCode.PASES,
				hoy.minusDays(5), hoy.plusDays(5));

		assertThat(vista.omitidas()).isEmpty();
		assertThat(valor(vista, "pases", "pases-packs-vendidos")).isEqualByComparingTo("4");
		assertThat(valor(vista, "pases", "pases-abonos-vendidos")).isEqualByComparingTo("0");
		assertThat(valor(vista, "pases", "pases-packs-anulados")).isEqualByComparingTo("1");
		assertThat(valor(vista, "pases", "pases-packs-vencidos")).isEqualByComparingTo("1");
		assertThat(valor(vista, "pases", "pases-creditos-vendidos")).isEqualByComparingTo("32");
		assertThat(valor(vista, "pases", "pases-creditos-consumidos")).isEqualByComparingTo("2");
		assertThat(valor(vista, "pases", "pases-creditos-devueltos")).isEqualByComparingTo("1");
		// -8 descartados al anular el pack 3, -2 del ajuste manual del pack 2.
		assertThat(valor(vista, "pases", "pases-creditos-ajustados")).isEqualByComparingTo("-10");
		assertThat(valor(vista, "pases", "pases-creditos-vencidos")).isEqualByComparingTo("8");
		// Vigentes hoy: pack 1 (8 - 1 = 7) y pack 2 (8 - 2 - 1 + 1 = 6).
		assertThat(valor(vista, "pases", "pases-packs-vigentes")).isEqualByComparingTo("2");
		assertThat(valor(vista, "pases", "pases-creditos-disponibles")).isEqualByComparingTo("13");
		assertThat(sumaDeSaldosVigentes(t, hoy)).isEqualTo(13);
		assertThat(vista.advertencias()).isEmpty();

		IndicadorDeReporte disponibles = indicador(vista, "pases", "pases-creditos-disponibles");
		assertThat(disponibles.fuente()).isEqualTo("M29 pase_servicio");
		assertThat(disponibles.criterioDeFecha()).contains("foto al " + hoy);

		assertThat(seccion(vista, "pases").filas())
				.extracting(f -> f.celdas().subList(1, 5))
				.containsExactly(
						List.of("PACK", "AJUSTE", "2", "-10"),
						List.of("PACK", "COMPRA", "4", "32"),
						List.of("PACK", "CONSUMO", "2", "-2"),
						List.of("PACK", "DEVOLUCION", "1", "1"),
						List.of("PACK", "VENCIMIENTO", "1", "-8"));

		// Un saldo que no cierra con su ledger se dice, no se muestra como si nada.
		jdbc.update("UPDATE pase_servicio SET creditos_disponibles = 5 WHERE id = ?", pack1);
		ReporteView divergente = reportes.generar(reporteDe(t), t.consultorioId(), ReporteCode.PASES,
				hoy.minusDays(5), hoy.plusDays(5));
		assertThat(divergente.advertencias())
				.extracting(a -> a.codigo())
				.containsExactly("saldo-de-pase-no-reconcilia");
		assertThat(exportador.aCsv(divergente)).contains("\"saldo-de-pase-no-reconcilia\",\"pases\",\"1 pase de la sede");
	}

	// =================================================================================
	// RF-M23-010 — abonos
	// =================================================================================

	@Test
	@DisplayName("PASES abonos: vendidos, renovados por el job, anulados, vencidos por periodo y vigentes hoy")
	void reporte_de_abonos() {
		Tenant t = fx.crearTenant(10, 3);
		declarar(t.ofertaId(), "POR_ABONO");
		long producto = productoService.crear(actorDe(t), t.consultorioId(), new CrearProductoCommand(
				t.ofertaId(), "Abono mensual " + UUID.randomUUID().toString().substring(0, 6), null, 8,
				new BigDecimal("30000.00"), "ARS", 31, LocalDate.now().minusDays(10), null,
				TipoProducto.ABONO)).id();
		LocalDate hoy = hoy();

		long renovable = ventaService.vender(actorDe(t), t.consultorioId(),
				new VenderPaseCommand(producto, t.persona(0), null, hoy.minusDays(2), null, true)).pase().id();
		jdbc.update("UPDATE pase_servicio SET vigencia_hasta = ? WHERE id = ?", hoy.plusDays(1), renovable);
		long terminado = ventaService.vender(actorDe(t), t.consultorioId(),
				new VenderPaseCommand(producto, t.persona(1), null, hoy.minusDays(2), null)).pase().id();
		jdbc.update("UPDATE pase_servicio SET vigencia_desde = ?, vigencia_hasta = ? WHERE id = ?",
				hoy.minusDays(40), hoy.minusDays(3), terminado);
		long anulado = ventaService.vender(actorDe(t), t.consultorioId(),
				new VenderPaseCommand(producto, t.persona(2), null, hoy.minusDays(2), null)).pase().id();
		ajusteService.anular(actorDe(t), t.consultorioId(), anulado, "Error de carga", 0);

		renovacion.renovarLosQueTerminan(3, 500);

		ReporteView vista = reportes.generar(reporteDe(t), t.consultorioId(), ReporteCode.PASES,
				hoy.minusDays(5), hoy.plusDays(5));

		assertThat(valor(vista, "pases", "pases-abonos-vendidos"))
				.as("tres del mostrador y el periodo que vendio la renovacion")
				.isEqualByComparingTo("4");
		assertThat(valor(vista, "pases", "pases-abonos-renovados")).isEqualByComparingTo("1");
		assertThat(valor(vista, "pases", "pases-abonos-anulados")).isEqualByComparingTo("1");
		assertThat(valor(vista, "pases", "pases-abonos-vencidos")).isEqualByComparingTo("1");
		// Vigente hoy solo el renovable: el renovado arranca pasado manana y el terminado ya termino.
		assertThat(valor(vista, "pases", "pases-abonos-vigentes")).isEqualByComparingTo("1");
		assertThat(valor(vista, "pases", "pases-creditos-disponibles")).isEqualByComparingTo("8");
		assertThat(valor(vista, "pases", "pases-packs-vendidos")).isEqualByComparingTo("0");
		assertThat(valor(vista, "pases", "pases-creditos-vendidos")).isEqualByComparingTo("32");
		assertThat(valor(vista, "pases", "pases-creditos-ajustados")).isEqualByComparingTo("-8");
		assertThat(vista.advertencias()).isEmpty();
	}

	// =================================================================================

	private void registrar(Tenant t, long clase, long persona, ResultadoAsistencia resultado, String motivo) {
		asistenciaService.registrar(actorDe(t), t.consultorioId(), clase,
				new RegistrarAsistenciaCommand(fx.inscripcionDe(clase, persona), resultado,
						OrigenAsistencia.MOSTRADOR, null, motivo));
	}

	private long vender(Tenant t, long producto, int persona) {
		return ventaService.vender(actorDe(t), t.consultorioId(),
				new VenderPaseCommand(producto, t.persona(persona), null)).pase().id();
	}

	private void declarar(long ofertaId, String esquema) {
		jdbc.update("UPDATE oferta_servicio_consultorio SET esquema_cobro = ?, momento_devengo = 'VENTA' "
				+ "WHERE id = ?", esquema, ofertaId);
	}

	private int sumaDeSaldosVigentes(Tenant t, LocalDate hoy) {
		return jdbc.queryForObject("""
				SELECT COALESCE(SUM(creditos_disponibles), 0) FROM pase_servicio
				 WHERE organization_id = ? AND estado = 'ACTIVO'
				   AND vigencia_desde <= ? AND vigencia_hasta >= ?
				""", Integer.class, t.organizationId(), hoy, hoy);
	}

	private long cuenta() {
		String email = "prof-reporte-" + UUID.randomUUID().toString().substring(0, 8) + "@ejemplo.test";
		jdbc.update("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetica', 'Profesional', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
		return jdbc.queryForObject("SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class, email);
	}

	private long membership(Tenant t, long cuenta, String rol) {
		jdbc.update("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", t.organizationId(), t.consultorioId(), cuenta, rol);
		return jdbc.queryForObject(
				"SELECT id FROM membership WHERE organization_id = ? AND account_id = ?",
				Long.class, t.organizationId(), cuenta);
	}

	private static com.akine.activity.application.OperatingActor actorDe(Tenant t) {
		return t.actor();
	}

	private static OperatingActor reporteDe(Tenant t) {
		return new OperatingActor(t.actor().accountId(), false, t.organizationId(), t.consultorioId());
	}

	private static LocalDate hoy() {
		return LocalDate.now(ZoneId.of(ActividadItFixture.ZONA));
	}

	private static AporteDeReporte seccion(ReporteView vista, String nombre) {
		return vista.secciones().stream()
				.filter(s -> s.seccion().equals(nombre))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Falta la seccion " + nombre + " en " + vista.secciones()));
	}

	private static IndicadorDeReporte indicador(ReporteView vista, String seccion, String clave) {
		return seccion(vista, seccion).indicadores().stream()
				.filter(i -> i.clave().equals(clave))
				.findFirst()
				.orElseThrow(() -> new AssertionError("Falta el indicador " + clave));
	}

	private static BigDecimal valor(ReporteView vista, String seccion, String clave) {
		return indicador(vista, seccion, clave).valor();
	}
}
