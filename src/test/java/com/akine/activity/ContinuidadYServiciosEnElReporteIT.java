package com.akine.activity;

import com.akine.testsupport.InsertarConId;
import com.akine.TestcontainersConfiguration;
import com.akine.activity.ActividadItFixture.Tenant;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.VenderPaseCommand;
import com.akine.activity.application.VentaDePaseService;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.billing.application.CajaService;
import com.akine.billing.application.CobroCommand;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.EgresoCommand;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.EgresoView;
import com.akine.billing.domain.CategoriaEgreso;
import com.akine.billing.domain.TipoBeneficiario;
import com.akine.billing.domain.exception.OfertaDelEgresoNoAccesibleException;
import com.akine.billing.domain.MedioDePago;
import com.akine.reporting.application.ExportadorCsv;
import com.akine.reporting.application.OperatingActor;
import com.akine.reporting.application.ReporteService;
import com.akine.reporting.application.ReporteView;
import com.akine.reporting.spi.AporteDeReporte;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AKINE-09.01b contra MySQL real: los reportes {@code CONTINUIDAD} (RF-M23-008) y
 * {@code SERVICIOS} (la parte de RF-M23-009 que tiene fuente).
 *
 * <p>Las ventas, los cobros, las inscripciones y las asistencias pasan por los servicios de verdad.
 * Las sesiones cerradas, las historias clinicas y las deudas de sesion van por JDBC, como en
 * {@code ReportesContraElMotorIT}: llegar a una sesion cerrada con conducta {@code ALTA} por
 * {@code encounter} pide turno, profesional y caso, y lo que se mide aca es el reporte, no el
 * cierre. Cada indicador se compara contra el numero que el escenario tiene que dar, escrito a mano.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ContinuidadYServiciosEnElReporteIT {

	private static final DateTimeFormatter DATETIME6 =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
	private static final AtomicInteger NUMERO_DE_SESION = new AtomicInteger(1);

	@Autowired private ReporteService reportes;
	@Autowired private ExportadorCsv exportador;
	@Autowired private InscripcionService inscripcionService;
	@Autowired private AsistenciaService asistenciaService;
	@Autowired private ProductoService productoService;
	@Autowired private VentaDePaseService ventaService;
	@Autowired private CajaService cajaService;
	@Autowired private CobroService cobroService;
	@Autowired private EgresoService egresoService;
	@Autowired private JdbcTemplate jdbc;

	private ActividadItFixture fx;

	@BeforeEach
	void setUp() {
		fx = new ActividadItFixture(jdbc, inscripcionService);
	}

	// =================================================================================
	// RF-M23-008 — continuidad del circuito
	// =================================================================================

	@Test
	@DisplayName("CONTINUIDAD: personas con alta que despues cierran una sesion o asisten a una clase, por naturaleza")
	void reporte_de_continuidad() {
		Tenant t = fx.crearTenant(10, 6);
		// La oferta grupal del fixture pasa a ser de un servicio PREVENTIVO: el Pilates.
		naturalezaDe(t.ofertaId(), "PREVENTIVO");
		long kinesio = oferta(t, "CLINICO", "INDIVIDUAL");
		long readaptacion = oferta(t, "TERAPEUTICO", "INDIVIDUAL");
		long membership = membershipDe(t);
		long[] hc = new long[5];
		for (int i = 0; i < 5; i++) {
			hc[i] = historia(t, t.persona(i));
		}
		Instant ahora = Instant.now();

		// p0: alta hace 3 dias; antes del alta, una sesion de readaptacion (no cuenta); despues,
		// asiste al Pilates -> PREVENTIVO.
		sesion(t, hc[0], readaptacion, membership, ahora.minus(4, ChronoUnit.DAYS), "CONTINUA");
		sesion(t, hc[0], kinesio, membership, ahora.minus(3, ChronoUnit.DAYS), "ALTA");
		// p1: alta hace 2 dias; despues, readaptacion y Pilates -> TERAPEUTICO y PREVENTIVO.
		sesion(t, hc[1], kinesio, membership, ahora.minus(2, ChronoUnit.DAYS), "ALTA");
		sesion(t, hc[1], readaptacion, membership, ahora.minus(1, ChronoUnit.DAYS), "CONTINUA");
		// p2: alta hace 2 dias; inscripto al Pilates pero ausente -> no pasa a ningun servicio.
		sesion(t, hc[2], kinesio, membership, ahora.minus(2, ChronoUnit.DAYS), "ALTA");
		// p3: dos altas en el periodo; entre las dos, una sesion de kinesio -> CLINICO, una vez.
		sesion(t, hc[3], kinesio, membership, ahora.minus(4, ChronoUnit.DAYS), "ALTA");
		sesion(t, hc[3], kinesio, membership, ahora.minus(2, ChronoUnit.DAYS), "CONTINUA");
		sesion(t, hc[3], kinesio, membership, ahora.minus(1, ChronoUnit.DAYS), "ALTA");
		// p4: alta fuera del periodo; asiste al Pilates -> no cuenta. p5: sin historia, asiste.
		sesion(t, hc[4], kinesio, membership, ahora.minus(20, ChronoUnit.DAYS), "ALTA");

		long clase = fx.programarClase(t, 10, 1);
		for (int i : new int[]{0, 1, 2, 4, 5}) {
			fx.inscribir(t, clase, t.persona(i), false);
		}
		asistenciaService.iniciar(t.actor(), t.consultorioId(), clase);
		for (int i : new int[]{0, 1, 4, 5}) {
			registrar(t, clase, t.persona(i), ResultadoAsistencia.PRESENTE);
		}
		registrar(t, clase, t.persona(2), ResultadoAsistencia.AUSENTE);

		// Otro tenant con un alta en el mismo periodo: no cuenta.
		Tenant otro = fx.crearTenant(10, 1);
		sesion(otro, historia(otro, otro.persona(0)), oferta(otro, "CLINICO", "INDIVIDUAL"),
				membershipDe(otro), ahora.minus(1, ChronoUnit.DAYS), "ALTA");

		LocalDate hoy = hoy();

		// Sin hc:read la seccion no se calcula: se omite y se declara (CA-M23-008-02).
		ReporteView sinPermiso = reportes.generar(reporteDe(t), t.consultorioId(), ReporteCode.CONTINUIDAD,
				hoy.minusDays(5), hoy.plusDays(5));
		assertThat(sinPermiso.secciones()).isEmpty();
		assertThat(sinPermiso.omitidas()).extracting(ReporteView.SeccionOmitida::seccion)
				.containsExactly("continuidad");

		otorgarHcRead(t, membership);
		ReporteView vista = reportes.generar(reporteDe(t), t.consultorioId(), ReporteCode.CONTINUIDAD,
				hoy.minusDays(5), hoy.plusDays(5));

		assertThat(vista.omitidas()).isEmpty();
		assertThat(valor(vista, "continuidad", "continuidad-altas")).isEqualByComparingTo("5");
		assertThat(valor(vista, "continuidad", "continuidad-personas-con-alta")).isEqualByComparingTo("4");
		assertThat(valor(vista, "continuidad", "continuidad-a-preventivo")).isEqualByComparingTo("2");
		assertThat(valor(vista, "continuidad", "continuidad-a-terapeutico")).isEqualByComparingTo("1");
		assertThat(valor(vista, "continuidad", "continuidad-a-clinico")).isEqualByComparingTo("1");
		assertThat(valor(vista, "continuidad", "continuidad-a-bienestar")).isEqualByComparingTo("0");
		assertThat(valor(vista, "continuidad", "continuidad-tasa-preventivo")).isEqualByComparingTo("50.00");

		String pilates = servicioDe(t.ofertaId());
		assertThat(seccion(vista, "continuidad").filas())
				.extracting(f -> f.celdas())
				.containsExactly(
						List.of("CLINICO", servicioDe(kinesio), "1"),
						List.of("TERAPEUTICO", servicioDe(readaptacion), "1"),
						List.of("PREVENTIVO", pilates, "2"));
		assertThat(indicador(vista, "continuidad", "continuidad-a-preventivo").fuente())
				.contains("M27 servicio.naturaleza");

		// Es una lectura clinica: el acceso queda auditado.
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE organization_id = ? AND event_type = 'REPORTE_CONSULTADO'
				""", Integer.class, t.organizationId())).isPositive();
		assertThat(exportador.aCsv(vista)).contains("\"Reporte\",\"CONTINUIDAD\"");
	}

	// =================================================================================
	// RF-M23-009 — ingresos por servicio
	// =================================================================================

	@Test
	@DisplayName("SERVICIOS: devengado y cobrado por oferta y concepto; consumir creditos no suma ingreso")
	void reporte_de_ingresos_por_servicio() {
		Tenant t = fx.crearTenant(10, 4);
		naturalezaDe(t.ofertaId(), "PREVENTIVO");
		declarar(t.ofertaId(), "POR_PACK");
		long kinesio = oferta(t, "CLINICO", "INDIVIDUAL");
		long membership = membershipDe(t);
		var actorDeCaja = new com.akine.billing.application.OperatingActor(
				t.actor().accountId(), false, t.organizationId(), t.consultorioId());
		cajaService.abrir(actorDeCaja, t.consultorioId(), BigDecimal.ZERO, "ARS");

		// Dos packs de Pilates de 8 creditos a 40000: 80000 devengados por VENTA_PASE.
		long producto = productoService.crear(t.actor(), t.consultorioId(),
				PaseVentaYConsumoIT.comando(t.ofertaId(), "Pack Pilates 8", 8)).id();
		vender(t, producto, 0);
		vender(t, producto, 1);
		cobrar(actorDeCaja, t, t.persona(0), deudaDeVentaDe(t.persona(0)), "40000.00");

		// Las dos asisten: consumen dos creditos y la deuda de packs NO cambia (CA-M23-009-06).
		long clase = fx.programarClase(t, 10, 1);
		fx.inscribir(t, clase, t.persona(0), false);
		fx.inscribir(t, clase, t.persona(1), false);
		asistenciaService.iniciar(t.actor(), t.consultorioId(), clase);
		registrar(t, clase, t.persona(0), ResultadoAsistencia.PRESENTE);
		registrar(t, clase, t.persona(1), ResultadoAsistencia.PRESENTE);

		// Una prestacion individual de 1000, cobrada 600; una atencion en la clase de 500, sin
		// cobrar; una deuda anulada de 999, que no suma; y un anticipo puro de 300.
		long hc2 = historia(t, t.persona(2));
		long deudaKinesio = deuda(t, hc2, t.persona(2), kinesio, membership, "1000.00", "PENDIENTE");
		cobrar(actorDeCaja, t, t.persona(2), deudaKinesio, "600.00");
		long hc3 = historia(t, t.persona(3));
		deuda(t, hc3, t.persona(3), t.ofertaId(), membership, "500.00", "PENDIENTE");
		deuda(t, hc3, t.persona(3), kinesio, membership, "999.00", "ANULADA");
		cobroService.registrar(actorDeCaja, t.consultorioId(), new CobroCommand(
				t.persona(3), new BigDecimal("300.00"),
				List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, new BigDecimal("300.00"), null)),
				List.of(), null, new BigDecimal("300.00"), "ARS"));

		// Otro tenant vende un pack en el mismo periodo: no cuenta.
		Tenant otro = fx.crearTenant(10, 1);
		declarar(otro.ofertaId(), "POR_PACK");
		vender(otro, productoService.crear(otro.actor(), otro.consultorioId(),
				PaseVentaYConsumoIT.comando(otro.ofertaId(), "Pack ajeno", 4)).id(), 0);

		LocalDate hoy = hoy();
		ReporteView vista = reportes.generar(reporteDe(t), t.consultorioId(), ReporteCode.SERVICIOS,
				hoy.minusDays(5), hoy.plusDays(5));

		assertThat(vista.omitidas()).isEmpty();
		assertThat(vista.secciones()).extracting(AporteDeReporte::seccion)
				.contains("ingresos-por-servicio", "creditos-por-servicio", "clases");

		String s = "ingresos-por-servicio";
		assertThat(valor(vista, s, "servicios-devengado-prestaciones")).isEqualByComparingTo("1000");
		assertThat(valor(vista, s, "servicios-devengado-clases")).isEqualByComparingTo("500");
		assertThat(valor(vista, s, "servicios-devengado-packs")).isEqualByComparingTo("80000");
		assertThat(valor(vista, s, "servicios-devengado-abonos")).isEqualByComparingTo("0");
		assertThat(valor(vista, s, "servicios-cobrado-prestaciones")).isEqualByComparingTo("600");
		assertThat(valor(vista, s, "servicios-cobrado-clases")).isEqualByComparingTo("0");
		assertThat(valor(vista, s, "servicios-cobrado-packs")).isEqualByComparingTo("40000");
		assertThat(valor(vista, s, "servicios-cobrado-abonos")).isEqualByComparingTo("0");
		assertThat(valor(vista, s, "servicios-anticipos-sin-imputar")).isEqualByComparingTo("300");

		String pilates = servicioDe(t.ofertaId());
		String ofertaPilates = ofertaNombre(t.ofertaId());
		assertThat(seccion(vista, s).filas())
				.extracting(f -> f.celdas())
				.containsExactly(
						List.of(servicioDe(kinesio), "CLINICO", ofertaNombre(kinesio),
								"Prestaciones individuales", "1000.00", "600.00"),
						List.of(pilates, "PREVENTIVO", ofertaPilates, "Clases", "500.00", "0"),
						List.of(pilates, "PREVENTIVO", ofertaPilates, "Packs", "80000.00", "40000.00"));
		assertThat(vista.advertencias()).extracting(a -> a.codigo()).contains("rentabilidad-sin-costos");

		String c = "creditos-por-servicio";
		assertThat(valor(vista, c, "servicios-creditos-vendidos")).isEqualByComparingTo("16");
		assertThat(valor(vista, c, "servicios-creditos-consumidos")).isEqualByComparingTo("2");
		assertThat(valor(vista, c, "servicios-creditos-devueltos")).isEqualByComparingTo("0");
		assertThat(seccion(vista, c).filas())
				.extracting(f -> f.celdas())
				.containsExactly(List.of(pilates, ofertaPilates, "PACK", "16", "2", "0"));

		// La seccion suma lo mismo que el producido del reporte economico: es esa plata, abierta.
		ReporteView economico = reportes.generar(reporteDe(t), t.consultorioId(), ReporteCode.ECONOMICO,
				hoy.minusDays(5), hoy.plusDays(5));
		assertThat(valor(economico, "economia", "devengado")).isEqualByComparingTo("81500");

		assertThat(exportador.aCsv(vista)).contains("\"Reporte\",\"SERVICIOS\"")
				.contains("rentabilidad-sin-costos");
	}

	// =================================================================================
	// DP-39 — rentabilidad y caja por servicio
	// =================================================================================

	@Test
	@DisplayName("SERVICIOS: rentabilidad = devengado - egresos imputados; caja de un cobro repartida por medio en proporcion a lo imputado")
	void rentabilidad_y_caja_por_servicio() {
		Tenant t = fx.crearTenant(10, 2);
		naturalezaDe(t.ofertaId(), "PREVENTIVO");
		long pilates = t.ofertaId();
		long kinesio = oferta(t, "CLINICO", "INDIVIDUAL");
		long membership = membershipDe(t);
		var actorDeCaja = new com.akine.billing.application.OperatingActor(
				t.actor().accountId(), false, t.organizationId(), t.consultorioId());
		cajaService.abrir(actorDeCaja, t.consultorioId(), BigDecimal.ZERO, "ARS");

		// Dos deudas de la misma persona: kinesio 1000 y Pilates 500.
		long hc = historia(t, t.persona(0));
		long deudaKinesio = deuda(t, hc, t.persona(0), kinesio, membership, "1000.00", "PENDIENTE");
		long deudaPilates = deuda(t, hc, t.persona(0), pilates, membership, "500.00", "PENDIENTE");

		// UN cobro de 1200: 100 en efectivo y 1100 por transferencia; imputa 600 a kinesio y 400 a
		// Pilates y deja 200 de saldo a favor. Pesos 600/400/200 sobre 1200:
		//   efectivo 100      -> 50.00 | 33.333.. | 16.666..  -> 50.00 | 33.33 | 16.67 (el centavo al mayor resto)
		//   transferencia 1100 -> 550.00 | 366.666.. | 183.333.. -> 550.00 | 366.67 | 183.33
		//   por fila           -> 600.00 | 400.00 | 200.00
		cobroService.registrar(actorDeCaja, t.consultorioId(), new CobroCommand(
				t.persona(0), new BigDecimal("1200.00"),
				List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, new BigDecimal("100.00"), null),
						new CobroCommand.MedioPedido(MedioDePago.TRANSFERENCIA, new BigDecimal("1100.00"), "T-1")),
				List.of(new CobroCommand.ImputacionPedida(deudaKinesio, new BigDecimal("600.00")),
						new CobroCommand.ImputacionPedida(deudaPilates, new BigDecimal("400.00"))),
				null, new BigDecimal("200.00"), "ARS"));

		// Egresos: 250 a kinesio y 150 a Pilates confirmados; 1000 de alquiler sin oferta; 999 a
		// kinesio confirmado y anulado (no suma); 777 a Pilates en borrador (no suma).
		egreso(actorDeCaja, t, kinesio, "250.00", "F-1", Fin.CONFIRMAR);
		egreso(actorDeCaja, t, pilates, "150.00", "F-2", Fin.CONFIRMAR);
		egreso(actorDeCaja, t, null, "1000.00", "F-3", Fin.CONFIRMAR);
		egreso(actorDeCaja, t, kinesio, "999.00", "F-4", Fin.ANULAR);
		egreso(actorDeCaja, t, pilates, "777.00", "F-5", Fin.BORRADOR);

		// Otro tenant: un egreso imputado a SU oferta no suma; y no puede imputar a la de este.
		Tenant otro = fx.crearTenant(10, 1);
		var actorOtro = new com.akine.billing.application.OperatingActor(
				otro.actor().accountId(), false, otro.organizationId(), otro.consultorioId());
		egreso(actorOtro, otro, otro.ofertaId(), "5000.00", "F-9", Fin.CONFIRMAR);
		assertThatThrownBy(() -> egreso(actorOtro, otro, kinesio, "10.00", "F-10", Fin.BORRADOR))
				.isInstanceOf(OfertaDelEgresoNoAccesibleException.class);

		LocalDate hoy = hoy();
		ReporteView vista = reportes.generar(reporteDe(t), t.consultorioId(), ReporteCode.SERVICIOS,
				hoy.minusDays(5), hoy.plusDays(5));
		String sinImputar = "(sin imputar)";

		String r = "rentabilidad-por-servicio";
		assertThat(valor(vista, r, "rentabilidad-ingresos")).isEqualByComparingTo("1500");
		assertThat(valor(vista, r, "rentabilidad-egresos-imputados")).isEqualByComparingTo("400");
		assertThat(valor(vista, r, "rentabilidad-egresos-sin-imputar")).isEqualByComparingTo("1000");
		assertThat(valor(vista, r, "rentabilidad-margen-de-servicios")).isEqualByComparingTo("1100");
		assertThat(valor(vista, r, "rentabilidad-resultado")).isEqualByComparingTo("100");
		assertThat(seccion(vista, r).filas())
				.extracting(f -> f.celdas())
				.containsExactlyInAnyOrder(
						List.of(servicioDe(kinesio), "CLINICO", ofertaNombre(kinesio), "1000.00", "250.00", "750.00"),
						List.of(servicioDe(pilates), "PREVENTIVO", ofertaNombre(pilates), "500.00", "150.00", "350.00"),
						List.of(sinImputar, "", "(egresos sin oferta)", "0", "1000.00", "-1000.00"));
		assertThat(vista.advertencias()).extracting(a -> a.codigo()).doesNotContain("rentabilidad-sin-costos");

		String c = "caja-por-servicio";
		assertThat(valor(vista, c, "servicios-caja-cobrado")).isEqualByComparingTo("1200");
		assertThat(valor(vista, c, "servicios-caja-imputado")).isEqualByComparingTo("1000");
		assertThat(valor(vista, c, "servicios-caja-sin-imputar")).isEqualByComparingTo("200");
		assertThat(seccion(vista, c).filas())
				.extracting(f -> f.celdas())
				.containsExactlyInAnyOrder(
						List.of(servicioDe(kinesio), "CLINICO", ofertaNombre(kinesio),
								"600.00", "50.00", "550.00", "0", "0", "0"),
						List.of(servicioDe(pilates), "PREVENTIVO", ofertaNombre(pilates),
								"400.00", "33.33", "366.67", "0", "0", "0"),
						List.of(sinImputar, "", "(anticipos y saldo a favor)",
								"200.00", "16.67", "183.33", "0", "0", "0"));
		assertThat(indicador(vista, c, "servicios-caja-cobrado").criterioDeFecha()).contains("generadoEn");

		// Las dos secciones cierran contra el reporte economico: la caja suma lo cobrado y la columna
		// de egresos (imputados + sin imputar) suma los egresos confirmados.
		ReporteView economico = reportes.generar(reporteDe(t), t.consultorioId(), ReporteCode.ECONOMICO,
				hoy.minusDays(5), hoy.plusDays(5));
		assertThat(valor(economico, "economia", "cobrado")).isEqualByComparingTo("1200");
		assertThat(valor(economico, "economia", "egresos-confirmados")).isEqualByComparingTo("1400");
		assertThat(valor(economico, "economia", "devengado")).isEqualByComparingTo("1500");

		assertThat(exportador.aCsv(vista)).contains("Resultado (margen - egresos sin imputar)")
				.contains("Cobrado en caja (todos los medios)").contains("(anticipos y saldo a favor)");
	}

	private enum Fin { BORRADOR, CONFIRMAR, ANULAR }

	private void egreso(com.akine.billing.application.OperatingActor actor, Tenant t, Long ofertaId,
			String importe, String comprobante, Fin fin) {
		EgresoView egreso = egresoService.registrar(actor, t.consultorioId(), new EgresoCommand(
				CategoriaEgreso.OTRO, TipoBeneficiario.EXTERNO, null, "Proveedor sintetico",
				null, null, null, "Egreso sintetico " + comprobante, new BigDecimal(importe), "ARS",
				"FACTURA_C", comprobante + "-" + UUID.randomUUID().toString().substring(0, 8),
				hoy(), null, ofertaId));
		assertThat(egreso.ofertaId()).isEqualTo(ofertaId);
		if (fin != Fin.BORRADOR) {
			egresoService.confirmar(actor, t.consultorioId(), egreso.id());
		}
		if (fin == Fin.ANULAR) {
			egresoService.anular(actor, t.consultorioId(), egreso.id(), "Sintetico");
		}
	}

	// =================================================================================
	// Fixture
	// =================================================================================

	private void registrar(Tenant t, long clase, long persona, ResultadoAsistencia resultado) {
		asistenciaService.registrar(t.actor(), t.consultorioId(), clase,
				new RegistrarAsistenciaCommand(fx.inscripcionDe(clase, persona), resultado,
						OrigenAsistencia.MOSTRADOR, null, null));
	}

	private void vender(Tenant t, long producto, int persona) {
		ventaService.vender(t.actor(), t.consultorioId(),
				new VenderPaseCommand(producto, t.persona(persona), null));
	}

	private void cobrar(com.akine.billing.application.OperatingActor actor, Tenant t, long persona,
			long obligacion, String importe) {
		BigDecimal total = new BigDecimal(importe);
		cobroService.registrar(actor, t.consultorioId(), new CobroCommand(persona, total,
				List.of(new CobroCommand.MedioPedido(MedioDePago.EFECTIVO, total, null)),
				List.of(new CobroCommand.ImputacionPedida(obligacion, total)), null));
	}

	private long deudaDeVentaDe(long persona) {
		return jdbc.queryForObject(
				"SELECT id FROM obligacion WHERE persona_id = ? AND origen = 'VENTA_PASE'",
				Long.class, persona);
	}

	private void declarar(long ofertaId, String esquema) {
		jdbc.update("UPDATE oferta_servicio_consultorio SET esquema_cobro = ?, momento_devengo = 'VENTA' "
				+ "WHERE id = ?", esquema, ofertaId);
	}

	private void naturalezaDe(long ofertaId, String naturaleza) {
		jdbc.update("""
				UPDATE servicio SET naturaleza = ?
				 WHERE id = (SELECT servicio_id FROM oferta_servicio_consultorio WHERE id = ?)
				""", naturaleza, ofertaId);
	}

	private String servicioDe(long ofertaId) {
		return jdbc.queryForObject("""
				SELECT s.nombre FROM servicio s
				  JOIN oferta_servicio_consultorio o ON o.servicio_id = s.id
				 WHERE o.id = ?
				""", String.class, ofertaId);
	}

	private String ofertaNombre(long ofertaId) {
		return jdbc.queryForObject(
				"SELECT nombre_comercial FROM oferta_servicio_consultorio WHERE id = ?",
				String.class, ofertaId);
	}

	/** Un servicio de esa naturaleza y su oferta en la sede del tenant. */
	private long oferta(Tenant t, String naturaleza, String modalidad) {
		String sufijo = UUID.randomUUID().toString().substring(0, 8);
		long servicio = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "CONT-" + sufijo.toUpperCase(), naturaleza + " " + sufijo, naturaleza, modalidad);
		return insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 30, 1, 1000.00, 'ARS', 0, 0, 0, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", t.organizationId(), t.consultorioId(), servicio, "Oferta " + naturaleza + " " + sufijo,
				modalidad);
	}

	private long membershipDe(Tenant t) {
		return jdbc.queryForObject(
				"SELECT id FROM membership WHERE organization_id = ? AND account_id = ?",
				Long.class, t.organizationId(), t.actor().accountId());
	}

	private long historia(Tenant t, long persona) {
		return insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", t.organizationId(), persona, t.actor().accountId());
	}

	/** Una sesion CERRADA en {@code cerradaEn} con esa proxima conducta. */
	private long sesion(Tenant t, long historia, long oferta, long membership, Instant cerradaEn,
			String conducta) {
		return insertar("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, numero_sesion, iniciada_en,
				                    iniciada_por_cuenta_id, cerrada_en, cerrada_por_cuenta_id,
				                    proxima_conducta, ultimo_numero_version, version,
				                    created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'CERRADA', ?, ?, ?, ?, ?, ?, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", t.organizationId(), t.consultorioId(), historia, oferta, membership,
				NUMERO_DE_SESION.getAndIncrement(), utc(cerradaEn), t.actor().accountId(),
				utc(cerradaEn), t.actor().accountId(), conducta);
	}

	/** Una sesion cerrada y su deuda del paciente, devengada ahora, en ese estado. */
	private long deuda(Tenant t, long historia, long persona, long oferta, long membership,
			String importe, String estado) {
		long sesion = sesion(t, historia, oferta, membership, Instant.now(), "CONTINUA");
		boolean anulada = "ANULADA".equals(estado);
		return insertar("""
				INSERT INTO obligacion (organization_id, consultorio_id, sesion_id, persona_id,
				                        responsable, importe_original, saldo, moneda, estado,
				                        oferta_id, snapshot_nombre, snapshot_precio, devengada_en,
				                        anulada_en, anulada_por_cuenta_id, motivo_anulacion,
				                        version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'PACIENTE', ?, ?, 'ARS', ?, ?, 'Sesion sintetica', ?,
				        UTC_TIMESTAMP(6), ?, ?, ?, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", t.organizationId(), t.consultorioId(), sesion, persona,
				new BigDecimal(importe), new BigDecimal(importe), estado, oferta, new BigDecimal(importe),
				anulada ? utc(Instant.now()) : null, anulada ? t.actor().accountId() : null,
				anulada ? "Sintetica" : null);
	}

	private void otorgarHcRead(Tenant t, long membership) {
		jdbc.update("""
				INSERT INTO membership_grant (organization_id, membership_id, permission_code,
				                              granted_by_account_id, reason, valid_from, active,
				                              version, created_at, updated_at)
				VALUES (?, ?, 'HC_READ', ?, 'sintetico', DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", t.organizationId(), membership, t.actor().accountId());
	}

	private static String utc(Instant instante) {
		return LocalDateTime.ofInstant(instante, ZoneOffset.UTC).format(DATETIME6);
	}

	private long insertar(String sql, Object... args) {
		return InsertarConId.insertar(jdbc, sql, args);
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
