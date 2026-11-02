package com.akine.reporting;

import com.akine.testsupport.InsertarConId;
import com.akine.TestcontainersConfiguration;
import com.akine.billing.application.CajaService;
import com.akine.billing.application.CobroCommand;
import com.akine.billing.application.CobroService;
import com.akine.billing.domain.MedioDePago;
import com.akine.reporting.application.AgrupacionDeSerie;
import com.akine.reporting.application.OperatingActor;
import com.akine.reporting.application.ReporteService;
import com.akine.reporting.application.ReporteView;
import com.akine.reporting.application.SerieDeReporteView;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import com.akine.reporting.spi.ReporteCode;
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
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Escenarios diferidos 49, 50, 51 y 53 de 07.06 ({@code docs/tests-diferidos.md}), contra MySQL
 * real (cierre de F8).
 *
 * <p>Los cuatro son de los que un unitario no puede ver: el filtro de tenant de una agregacion,
 * la resta de dos sumas que salen de dos tablas, {@code CONVERT_TZ} con las tablas de zonas del
 * motor y la precision {@code DATETIME(6)} contra un limite exclusivo. Se entra por
 * {@link ReporteService} con un {@code ADMINISTRATIVO}, que segun la matriz ve turnos y caja de
 * su sede; los cobros se registran por {@link CobroService} con la caja abierta por
 * {@link CajaService}, porque lo que se reconcilia es lo que esos servicios escriben.
 *
 * <p>El escenario 52 (EXPLAIN de las agregaciones) no esta aca: sobre las pocas filas de un IT el
 * optimizador elige {@code ALL} aunque el indice exista, y el test diria lo contrario de lo que
 * pasa con datos reales. Ver la ficha de F8.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ReportesContraElMotorIT {

	private static final String CORDOBA = "America/Argentina/Cordoba";
	private static final DateTimeFormatter DATETIME6 =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");

	@Autowired private ReporteService reportes;
	@Autowired private CajaService cajaService;
	@Autowired private CobroService cobroService;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// 49 — aislamiento de tenant de las agregaciones
	// =================================================================================

	@Test
	@DisplayName("49 · con dos tenants en el mismo periodo, los totales de A no suman una fila de B")
	void los_totales_de_un_tenant_no_incluyen_al_otro() {
		Centro a = crearCentro(CORDOBA);
		Centro b = crearCentro(CORDOBA);
		Instant ahora = Instant.now();
		for (int i = 1; i <= 2; i++) {
			turno(a, ahora.plusSeconds(i * 3600L));
		}
		for (int i = 1; i <= 3; i++) {
			turno(b, ahora.plusSeconds(i * 3600L));
		}
		cobrarEfectivo(a, "1000.00");
		cobrarEfectivo(b, "7000.00");
		cobrarEfectivo(b, "9000.00");

		LocalDate hoy = LocalDate.now(ZoneId.of(CORDOBA));
		ReporteView vistaA = reportes.generar(a.actor(), a.consultorioId(), ReporteCode.OPERATIVO,
				hoy.minusDays(1), hoy.plusDays(1));
		ReporteView vistaB = reportes.generar(b.actor(), b.consultorioId(), ReporteCode.OPERATIVO,
				hoy.minusDays(1), hoy.plusDays(1));

		assertThat(valor(vistaA, "turnos", "turnos-totales")).isEqualByComparingTo("2");
		assertThat(valor(vistaB, "turnos", "turnos-totales")).isEqualByComparingTo("3");
		assertThat(valor(vistaA, "economia", "cobrado")).isEqualByComparingTo("1000.00");
		assertThat(valor(vistaA, "economia", "devengado")).isEqualByComparingTo("1000.00");
		assertThat(valor(vistaA, "economia", "caja-ingresos-efectivo"))
				.isEqualByComparingTo("1000.00");
		assertThat(valor(vistaB, "economia", "cobrado")).isEqualByComparingTo("16000.00");
		assertThat(valor(vistaB, "economia", "conciliacion-caja-de-cobros"))
				.isEqualByComparingTo("16000.00");
		// El 404 de la sede ajena ya lo prueba ReporteReadIT.otro_tenant_es_404.
	}

	// =================================================================================
	// 50 — la reconciliacion cierra
	// =================================================================================

	@Test
	@DisplayName("50 · efectivo, mixto y tarjeta: la conciliacion suma solo el efectivo y la diferencia da cero")
	void la_conciliacion_da_cero() {
		Centro centro = crearCentro(CORDOBA);
		cobrar(centro, "8500.00", List.of(medio(MedioDePago.EFECTIVO, "8500.00")));
		cobrar(centro, "8500.00", List.of(
				medio(MedioDePago.EFECTIVO, "5000.00"), medio(MedioDePago.TARJETA_DEBITO, "3500.00")));
		cobrar(centro, "8500.00", List.of(medio(MedioDePago.TARJETA_CREDITO, "8500.00")));

		LocalDate hoy = LocalDate.now(ZoneId.of(CORDOBA));
		ReporteView vista = reportes.generar(centro.actor(), centro.consultorioId(),
				ReporteCode.ECONOMICO, hoy, hoy);

		assertThat(valor(vista, "economia", "cobrado")).isEqualByComparingTo("25500.00");
		assertThat(valor(vista, "economia", "conciliacion-cobrado-efectivo"))
				.isEqualByComparingTo("13500.00");
		assertThat(valor(vista, "economia", "conciliacion-caja-de-cobros"))
				.as("la caja solo recibe la parte en efectivo")
				.isEqualByComparingTo("13500.00");
		assertThat(valor(vista, "economia", "conciliacion-diferencia"))
				.isEqualByComparingTo(BigDecimal.ZERO);
	}

	// =================================================================================
	// 51 — CONVERT_TZ y el turno de las 21:30
	// =================================================================================

	@Test
	@DisplayName("51 · un turno de las 21:30 de la sede cae en su dia, y el mismo instante en otra zona agrupa distinto")
	void el_turno_de_la_noche_cae_en_el_dia_de_la_sede() {
		LocalDate dia = LocalDate.of(2026, 3, 10);
		// 21:30 en Cordoba (UTC-3) es 00:30 UTC del dia siguiente: sin CONVERT_TZ caeria el 11.
		Instant instante = dia.atTime(21, 30).atZone(ZoneId.of(CORDOBA)).toInstant();

		Centro cordoba = crearCentro(CORDOBA);
		turno(cordoba, instante);
		Centro tokio = crearCentro("Asia/Tokyo");
		turno(tokio, instante);

		ReporteView vistaCordoba = reportes.generar(cordoba.actor(), cordoba.consultorioId(),
				ReporteCode.TURNOS, dia.minusDays(1), dia.plusDays(2));
		ReporteView vistaTokio = reportes.generar(tokio.actor(), tokio.consultorioId(),
				ReporteCode.TURNOS, dia.minusDays(1), dia.plusDays(2));

		assertThat(diasDelDetalle(vistaCordoba))
				.as("el motor tiene cargadas las zonas: si no, el IFNULL lo mandaria al 11")
				.containsExactly("2026-03-10");
		assertThat(diasDelDetalle(vistaTokio)).containsExactly("2026-03-11");
	}

	// =================================================================================
	// 53 — los bordes del rango, inclusive de los dos lados
	// =================================================================================

	@Test
	@DisplayName("53 · 00:00:00.000000 del desde entra, 23:59:59.999999 del hasta entra, 00:00 del dia siguiente no")
	void los_bordes_del_rango() {
		ZoneId zona = ZoneId.of(CORDOBA);
		LocalDate desde = LocalDate.of(2026, 4, 1);
		LocalDate hasta = LocalDate.of(2026, 4, 3);
		Centro centro = crearCentro(CORDOBA);
		turno(centro, desde.atStartOfDay(zona).toInstant());
		turno(centro, hasta.atTime(LocalTime.of(23, 59, 59, 999_999_000)).atZone(zona).toInstant());
		turno(centro, hasta.plusDays(1).atStartOfDay(zona).toInstant());
		turno(centro, desde.atStartOfDay(zona).toInstant().minusNanos(1_000));

		ReporteView vista = reportes.generar(centro.actor(), centro.consultorioId(),
				ReporteCode.TURNOS, desde, hasta);

		assertThat(valor(vista, "turnos", "turnos-totales"))
				.as("solo los dos de adentro; el microsegundo anterior y la medianoche siguiente quedan afuera")
				.isEqualByComparingTo("2");
	}

	@Test
	@DisplayName("53 · la fecha de negocio de la caja entra con su dia y no con el siguiente")
	void la_fecha_de_negocio_entra_por_su_dia() {
		Centro centro = crearCentro(CORDOBA);
		cobrarEfectivo(centro, "4200.00");
		LocalDate hoy = LocalDate.now(ZoneId.of(CORDOBA));

		ReporteView ese = reportes.generar(centro.actor(), centro.consultorioId(),
				ReporteCode.ECONOMICO, hoy, hoy);
		ReporteView siguiente = reportes.generar(centro.actor(), centro.consultorioId(),
				ReporteCode.ECONOMICO, hoy.plusDays(1), hoy.plusDays(1));

		assertThat(valor(ese, "economia", "conciliacion-caja-de-cobros"))
				.isEqualByComparingTo("4200.00");
		assertThat(valor(siguiente, "economia", "conciliacion-caja-de-cobros"))
				.isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(valor(siguiente, "economia", "cobrado")).isEqualByComparingTo(BigDecimal.ZERO);
	}

	// =================================================================================
	// DP-45 — la serie temporal usa la fuente y el criterio de su indicador
	// =================================================================================

	@Test
	@DisplayName("DP-45 · cada serie economica suma exactamente su indicador, y cae en el dia de la sede")
	void cada_serie_economica_suma_su_indicador() {
		Centro centro = crearCentro(CORDOBA);
		cobrarEfectivo(centro, "1000.00");
		cobrar(centro, "8500.00", List.of(
				medio(MedioDePago.EFECTIVO, "5000.00"), medio(MedioDePago.TARJETA_DEBITO, "3500.00")));
		LocalDate hoy = LocalDate.now(ZoneId.of(CORDOBA));

		ReporteView vista = reportes.generar(centro.actor(), centro.consultorioId(),
				ReporteCode.ECONOMICO, hoy.minusDays(2), hoy.plusDays(2));
		SerieDeReporteView serie = reportes.generarSerie(centro.actor(), centro.consultorioId(),
				ReporteCode.ECONOMICO, hoy.minusDays(2), hoy.plusDays(2), AgrupacionDeSerie.DIA);

		assertThat(serie.series()).extracting(SerieDeReporteView.Serie::clave)
				.containsExactly("devengado", "cobrado", "caja-ingresos-efectivo",
						"caja-egresos-efectivo", "egresos-confirmados");
		for (SerieDeReporteView.Serie s : serie.series()) {
			assertThat(s.puntos()).hasSize(5);
			assertThat(s.total())
					.as("la serie %s y su indicador son la misma cuenta", s.clave())
					.isEqualByComparingTo(valor(vista, "economia", s.clave()));
			assertThat(s.criterioDeFecha()).isEqualTo(indicador(vista, "economia", s.clave()).criterioDeFecha());
		}
		SerieDeReporteView.Serie cobrado = serieDe(serie, "cobrado");
		assertThat(cobrado.total()).isEqualByComparingTo("9500.00");
		assertThat(cobrado.puntos().get(2).desde()).isEqualTo(hoy);
		assertThat(cobrado.puntos().get(2).valor()).isEqualByComparingTo("9500.00");
		assertThat(serieDe(serie, "caja-ingresos-efectivo").total())
				.as("solo el efectivo entra al cajon")
				.isEqualByComparingTo("6000.00");
	}

	@Test
	@DisplayName("DP-45 · en la serie, el turno de las 21:30 de Cordoba cae el 10 y el mismo instante en Tokio el 11")
	void la_serie_agrupa_por_el_dia_de_la_sede() {
		LocalDate dia = LocalDate.of(2026, 3, 10);
		Instant instante = dia.atTime(21, 30).atZone(ZoneId.of(CORDOBA)).toInstant();
		Centro cordoba = crearCentro(CORDOBA);
		turno(cordoba, instante);
		Centro tokio = crearCentro("Asia/Tokyo");
		turno(tokio, instante);

		assertThat(diasConValor(reportes.generarSerie(cordoba.actor(), cordoba.consultorioId(),
				ReporteCode.TURNOS, dia.minusDays(1), dia.plusDays(2), AgrupacionDeSerie.DIA), "turnos-totales"))
				.containsExactly(dia);
		assertThat(diasConValor(reportes.generarSerie(tokio.actor(), tokio.consultorioId(),
				ReporteCode.TURNOS, dia.minusDays(1), dia.plusDays(2), AgrupacionDeSerie.DIA), "turnos-totales"))
				.containsExactly(dia.plusDays(1));

		SerieDeReporteView semanal = reportes.generarSerie(cordoba.actor(), cordoba.consultorioId(),
				ReporteCode.TURNOS, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), AgrupacionDeSerie.SEMANA);
		assertThat(serieDe(semanal, "turnos-totales").puntos())
				.filteredOn(p -> p.valor().signum() > 0)
				.singleElement()
				.satisfies(p -> {
					assertThat(p.desde()).isEqualTo(LocalDate.of(2026, 3, 9));
					assertThat(p.hasta()).isEqualTo(LocalDate.of(2026, 3, 15));
				});
	}

	@Test
	@DisplayName("DP-45 · con horario de verano cada turno cae en su dia a los dos lados de la transicion")
	void la_serie_respeta_el_cambio_de_horario() {
		ZoneId madrid = ZoneId.of("Europe/Madrid");
		Centro centro = crearCentro("Europe/Madrid");
		// Antes del cambio (+01:00) y despues (+02:00). El segundo, con el desfase de antes, caeria
		// el 29 a las 23:30 en vez del 30 a las 00:30.
		turno(centro, LocalDate.of(2026, 3, 28).atTime(23, 30).atZone(madrid).toInstant());
		turno(centro, LocalDate.of(2026, 3, 30).atTime(0, 30).atZone(madrid).toInstant());

		SerieDeReporteView serie = reportes.generarSerie(centro.actor(), centro.consultorioId(),
				ReporteCode.TURNOS, LocalDate.of(2026, 3, 27), LocalDate.of(2026, 3, 31), AgrupacionDeSerie.DIA);

		assertThat(diasConValor(serie, "turnos-totales"))
				.containsExactly(LocalDate.of(2026, 3, 28), LocalDate.of(2026, 3, 30));
	}

	private static SerieDeReporteView.Serie serieDe(SerieDeReporteView vista, String clave) {
		return vista.series().stream()
				.filter(s -> s.clave().equals(clave))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No hay serie " + clave));
	}

	private static List<LocalDate> diasConValor(SerieDeReporteView vista, String clave) {
		return serieDe(vista, clave).puntos().stream()
				.filter(p -> p.valor().signum() > 0)
				.map(SerieDeReporteView.Punto::desde)
				.toList();
	}

	private static IndicadorDeReporte indicador(ReporteView vista, String seccion, String clave) {
		return seccion(vista, seccion).indicadores().stream()
				.filter(i -> i.clave().equals(clave))
				.findFirst()
				.orElseThrow(() -> new AssertionError("No hay indicador " + clave));
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private static BigDecimal valor(ReporteView vista, String seccion, String clave) {
		return seccion(vista, seccion).indicadores().stream()
				.filter(indicador -> indicador.clave().equals(clave))
				.map(IndicadorDeReporte::valor)
				.findFirst()
				.orElseThrow(() -> new AssertionError("No hay indicador " + clave));
	}

	private static AporteDeReporte seccion(ReporteView vista, String seccion) {
		return vista.secciones().stream()
				.filter(aporte -> aporte.seccion().equals(seccion))
				.findFirst()
				.orElseThrow(() -> new AssertionError("El reporte no trae la seccion " + seccion
						+ "; omitidas: " + vista.omitidas()));
	}

	private static List<String> diasDelDetalle(ReporteView vista) {
		return seccion(vista, "turnos").filas().stream()
				.map(fila -> fila.celdas().get(0))
				.distinct()
				.toList();
	}

	private static CobroCommand.MedioPedido medio(MedioDePago medio, String importe) {
		return new CobroCommand.MedioPedido(medio, new BigDecimal(importe), null);
	}

	private void cobrarEfectivo(Centro centro, String importe) {
		cobrar(centro, importe, List.of(medio(MedioDePago.EFECTIVO, importe)));
	}

	private void cobrar(Centro centro, String importe, List<CobroCommand.MedioPedido> medios) {
		BigDecimal total = new BigDecimal(importe);
		long obligacionId = deuda(centro, total);
		cobroService.registrar(centro.actorDeCaja(), centro.consultorioId(), new CobroCommand(
				centro.personaId(), total, medios,
				List.of(new CobroCommand.ImputacionPedida(obligacionId, total)), null));
	}

	// =================================================================================
	// Fixture — insercion directa y sintetica (AGENT.md §10), como CierreDeCajaConcurrenteIT
	// =================================================================================

	private record Centro(
			long organizationId, long consultorioId, long cuentaId, long membershipProfesional,
			long ofertaId, long personaId, long historiaId,
			OperatingActor actor, com.akine.billing.application.OperatingActor actorDeCaja) {
	}

	/** Una sede con un ADMINISTRATIVO, un profesional, una oferta, un paciente y la caja abierta. */
	private Centro crearCentro(String zona) {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + sufijo, "motor-it-" + sufijo, zona);
		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "Sede " + sufijo, zona);

		long cuentaId = crearCuenta("administrativo");
		membership(organizationId, consultorioId, cuentaId, "ADMINISTRATIVO");
		long cuentaProfesional = crearCuenta("profesional");
		long membershipProfesional =
				membership(organizationId, consultorioId, cuentaProfesional, "PROFESIONAL");

		long servicioId = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "MOTOR-" + sufijo.toUpperCase(), "Servicio " + sufijo);
		long ofertaId = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 30, 1, 1000.00, 'ARS', 0, 0, 0, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, servicioId, "Oferta " + sufijo);

		String apellido = "Paciente" + sufijo;
		long personaId = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, apellido, apellido.toUpperCase());
		long historiaId = insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, personaId, cuentaId);

		var actorDeCaja = new com.akine.billing.application.OperatingActor(
				cuentaId, false, organizationId, consultorioId);
		cajaService.abrir(actorDeCaja, consultorioId, BigDecimal.ZERO, "ARS");

		return new Centro(organizationId, consultorioId, cuentaId, membershipProfesional, ofertaId,
				personaId, historiaId,
				new OperatingActor(cuentaId, false, organizationId, consultorioId), actorDeCaja);
	}

	/** Una sesion en borrador y su deuda del paciente, lista para cobrar. */
	private long deuda(Centro centro, BigDecimal importe) {
		long sesionId = insertar("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", centro.organizationId(), centro.consultorioId(), centro.historiaId(),
				centro.ofertaId(), centro.membershipProfesional(), centro.cuentaId());
		return insertar("""
				INSERT INTO obligacion (organization_id, consultorio_id, sesion_id, persona_id,
				                        responsable, importe_original, saldo, moneda, estado,
				                        oferta_id, snapshot_nombre, snapshot_precio, devengada_en,
				                        version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'PACIENTE', ?, ?, 'ARS', 'PENDIENTE',
				        ?, 'Sesion sintetica', ?, UTC_TIMESTAMP(6), 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", centro.organizationId(), centro.consultorioId(), sesionId, centro.personaId(),
				importe, importe, centro.ofertaId(), importe);
	}

	/** Un turno RESERVADO de 15 minutos que empieza en {@code inicio}, escrito con microsegundos. */
	private void turno(Centro centro, Instant inicio) {
		jdbc.update("""
				INSERT INTO turno (organization_id, consultorio_id, oferta_id, persona_id,
				                   profesional_membership_id, inicio, fin, estado,
				                   reservado_por_cuenta_id, reservado_en, version,
				                   created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, 'RESERVADO', ?, UTC_TIMESTAMP(6), 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", centro.organizationId(), centro.consultorioId(), centro.ofertaId(),
				centro.personaId(), centro.membershipProfesional(), utc(inicio),
				utc(inicio.plusSeconds(15 * 60)), centro.cuentaId());
	}

	/** Literal UTC con seis decimales: el {@code Instant} no pasa por la zona del driver. */
	private static String utc(Instant instante) {
		return LocalDateTime.ofInstant(instante, ZoneOffset.UTC).format(DATETIME6);
	}

	private long membership(long organizationId, long consultorioId, long cuenta, String rol) {
		return insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, cuenta, rol);
	}

	private long crearCuenta(String etiqueta) {
		String email = "motor-it-" + etiqueta + "-" + UUID.randomUUID() + "@ejemplo.test";
		return insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetica', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
	}

	private long insertar(String sql, Object... args) {
		return InsertarConId.insertar(jdbc, sql, args);
	}
}
