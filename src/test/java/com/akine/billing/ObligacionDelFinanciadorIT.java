package com.akine.billing;

import com.akine.TestcontainersConfiguration;
import com.akine.billing.application.ObligacionService;
import com.akine.billing.application.ObligacionView;
import com.akine.billing.application.OperatingActor;
import com.akine.billing.application.PresentacionCommands;
import com.akine.billing.application.PresentacionService;
import com.akine.billing.application.PresentacionView;
import com.akine.billing.domain.EstadoPresentacion;
import com.akine.billing.domain.exception.FinanciadorNoAccesibleException;
import com.akine.billing.infrastructure.FinanciadoresEnElReporte;
import com.akine.billing.infrastructure.ObligacionDevengador;
import com.akine.encounter.application.SesionService;
import com.akine.encounter.application.SesionView;
import com.akine.encounter.domain.Asistencia;
import com.akine.encounter.domain.CierreDeSesion;
import com.akine.encounter.spi.SesionCerrada;
import com.akine.reporting.spi.AporteDeReporte;
import com.akine.reporting.spi.ConsultaDeReporte;
import com.akine.reporting.spi.IndicadorDeReporte;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AKINE F-4 contra MySQL real: el cierre de una sesion cubierta por un convenio devenga la parte
 * del financiador y el coseguro, y esas filas llegan a la bandeja de presentaciones (07.04) y al
 * reporte de financiadores (07.06). <b>Es el criterio de exito de la ruta critica del MVP.</b>
 *
 * <p>Todo entra por el <b>cierre real</b> ({@code SesionService#cerrar}): es lo unico que prueba
 * el cableado entero —{@code encounter} lee precio, obra social y tratamientos; {@code billing}
 * pregunta a {@code offering}, {@code person} y {@code contracting}, congela y devenga— y que las
 * filas pasan las CHECK de V77.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ObligacionDelFinanciadorIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private SesionService sesionService;
	@Autowired private ObligacionService obligacionService;
	@Autowired private PresentacionService presentacionService;
	@Autowired private ObligacionDevengador devengador;
	@Autowired private FinanciadoresEnElReporte financiadoresEnElReporte;
	@Autowired private JdbcTemplate jdbc;

	// =================================================================================
	// El reparto
	// =================================================================================

	@Test
	@DisplayName("con cobertura y arancel: financiador 10500 + coseguro 1500 = arancel 12000, con snapshot")
	void financiador_y_coseguro_con_snapshot() {
		Fixture f = crearFixture(true);
		long sesion = insertarSesion(f);
		tratar(f, sesion, f.kine());

		cerrar(f, sesion);

		List<Map<String, Object>> filas = obligacionesDe(sesion);
		assertThat(filas).hasSize(2);
		Map<String, Object> financiador = filas.get(0);
		Map<String, Object> coseguro = filas.get(1);

		assertThat(financiador.get("concepto")).isEqualTo("FINANCIADOR");
		assertThat(financiador.get("responsable")).isEqualTo("FINANCIADOR");
		assertThat(((Number) financiador.get("financiador_id")).longValue()).isEqualTo(f.financiador());
		assertThat((BigDecimal) financiador.get("importe_original")).isEqualByComparingTo("10500.00");
		assertThat(coseguro.get("concepto")).isEqualTo("COSEGURO");
		assertThat(coseguro.get("responsable")).isEqualTo("PACIENTE");
		assertThat(coseguro.get("financiador_id")).isNull();
		assertThat((BigDecimal) coseguro.get("importe_original")).isEqualByComparingTo("1500.00");
		assertThat(((BigDecimal) financiador.get("importe_original"))
				.add((BigDecimal) coseguro.get("importe_original")))
				.isEqualByComparingTo("12000.00");

		for (Map<String, Object> fila : filas) {
			assertThat(((Number) fila.get("snapshot_convenio_id")).longValue()).isEqualTo(f.convenio());
			assertThat(((Number) fila.get("snapshot_arancel_id")).longValue()).isEqualTo(f.arancel());
			assertThat(((Number) fila.get("snapshot_plan_id")).longValue()).isEqualTo(f.plan());
			assertThat(((Number) fila.get("cobertura_id")).longValue()).isEqualTo(f.cobertura());
			assertThat(((Number) fila.get("practica_id")).longValue()).isEqualTo(f.kine());
			assertThat(fila.get("snapshot_convenio_codigo")).isEqualTo(f.convenioCodigo());
			assertThat((BigDecimal) fila.get("snapshot_importe_total")).isEqualByComparingTo("12000.00");
			assertThat((BigDecimal) fila.get("snapshot_importe_financiador")).isEqualByComparingTo("10500.00");
			assertThat((BigDecimal) fila.get("snapshot_coseguro")).isEqualByComparingTo("1500.00");
			// RF-M21-003: lo que el convenio exigia ese dia, y la credencial vencida (B-2).
			assertThat(bool(fila.get("snapshot_requeria_orden"))).isTrue();
			assertThat(bool(fila.get("snapshot_requeria_autorizacion"))).isFalse();
			assertThat(bool(fila.get("snapshot_requeria_credencial"))).isTrue();
			assertThat(bool(fila.get("snapshot_credencial_vencida"))).isTrue();
			assertThat(fila.get("moneda")).isEqualTo("ARS");
		}

		// La copia no cambia aunque el convenio cambie despues (RN-M16-003).
		jdbc.update("UPDATE convenio_arancel SET importe_total = 99999.00, importe_financiador = "
				+ "99999.00, coseguro = 0 WHERE id = ?", f.arancel());
		assertThat((BigDecimal) obligacionesDe(sesion).get(0).get("importe_original"))
				.isEqualByComparingTo("10500.00");
	}

	@Test
	@DisplayName("la cuenta corriente del paciente muestra el coseguro y NO la parte del financiador")
	void cuenta_corriente_del_paciente_solo_lo_suyo() {
		Fixture f = crearFixture(true);
		long sesion = insertarSesion(f);
		tratar(f, sesion, f.kine());

		cerrar(f, sesion);

		List<ObligacionView> cuenta = obligacionService.deLaPersona(
				f.administrativo(), f.consultorioId(), f.personaId());
		assertThat(cuenta).singleElement().satisfies(o -> {
			assertThat(o.concepto()).isEqualTo("COSEGURO");
			assertThat(o.importeOriginal()).isEqualByComparingTo("1500.00");
			assertThat(o.convenio()).isNotNull();
			assertThat(o.convenio().requeriaOrden()).isTrue();
		});
	}

	@Test
	@DisplayName("sin cobertura vigente: una sola obligacion particular por el precio de la oferta")
	void sin_cobertura_particular() {
		Fixture f = crearFixture(true);
		jdbc.update("UPDATE cobertura_paciente SET vigencia_hasta = ? WHERE id = ?",
				hoy().minusDays(1), f.cobertura());
		long sesion = insertarSesion(f);
		tratar(f, sesion, f.kine());

		cerrar(f, sesion);

		List<Map<String, Object>> filas = obligacionesDe(sesion);
		assertThat(filas).singleElement().satisfies(fila -> {
			assertThat(fila.get("concepto")).isEqualTo("PARTICULAR");
			assertThat(fila.get("responsable")).isEqualTo("PACIENTE");
			assertThat((BigDecimal) fila.get("importe_original")).isEqualByComparingTo("8500.00");
			assertThat(fila.get("snapshot_convenio_id")).isNull();
		});
	}

	@Test
	@DisplayName("oferta que no admite obra social: particular aunque haya cobertura y convenio")
	void oferta_sin_obra_social_particular() {
		Fixture f = crearFixture(false);
		long sesion = insertarSesion(f);
		tratar(f, sesion, f.kine());

		cerrar(f, sesion);

		assertThat(obligacionesDe(sesion)).singleElement()
				.satisfies(fila -> assertThat(fila.get("concepto")).isEqualTo("PARTICULAR"));
	}

	@Test
	@DisplayName("sin tratamientos: se factura la practica principal de la oferta (DP-11)")
	void sin_tratamientos_usa_la_principal() {
		Fixture f = crearFixture(true);
		long sesion = insertarSesion(f);

		cerrar(f, sesion);

		assertThat(obligacionesDe(sesion)).hasSize(2)
				.allSatisfy(fila -> assertThat(((Number) fila.get("practica_id")).longValue())
						.isEqualTo(f.kine()));
	}

	// =================================================================================
	// Idempotencia y concurrencia
	// =================================================================================

	@Test
	@DisplayName("re-disparo del cierre y del observador: siguen siendo dos filas")
	void redisparo_idempotente() {
		Fixture f = crearFixture(true);
		long sesion = insertarSesion(f);
		tratar(f, sesion, f.kine());
		cerrar(f, sesion);

		// El cierre repetido sale temprano (RN-M14-005)...
		cerrar(f, sesion);
		// ...y el observador disparado otra vez a mano tampoco agrega nada.
		devengador.alCerrar(new SesionCerrada(sesion, f.organizationId(), f.consultorioId(),
				f.personaId(), f.ofertaId(), 1, true, Instant.now(), f.profesionalCuentaId(),
				new BigDecimal("8500.00"), "ARS", Set.of(f.kine()), null, true));

		assertThat(obligacionesDe(sesion)).extracting(fila -> fila.get("concepto"))
				.containsExactly("FINANCIADOR", "COSEGURO");
	}

	@Test
	@DisplayName("dos cierres concurrentes de la misma sesion: exactamente financiador + coseguro")
	void dos_cierres_concurrentes() {
		Fixture f = crearFixture(true);
		long sesion = insertarSesion(f);
		tratar(f, sesion, f.kine());
		long version = sesionService.ver(f.profesional(), f.consultorioId(), sesion).version();

		List<Callable<?>> tareas = List.of(
				() -> cerrarConVersion(f, sesion, version),
				() -> cerrarConVersion(f, sesion, version));
		List<PresentacionItFixture.Desenlace> desenlaces = PresentacionItFixture.enParalelo(tareas);

		assertThat(desenlaces).as("%s", desenlaces).anyMatch(d -> !d.fallo());
		assertThat(obligacionesDe(sesion)).extracting(fila -> fila.get("concepto"))
				.containsExactly("FINANCIADOR", "COSEGURO");
	}

	// =================================================================================
	// El criterio de exito: bandeja y reporte
	// =================================================================================

	@Test
	@DisplayName("la parte del financiador aparece en la bandeja de elegibles y se puede presentar")
	void aparece_en_la_bandeja_y_se_presenta() {
		Fixture f = crearFixture(true);
		long sesion = insertarSesion(f);
		tratar(f, sesion, f.kine());
		cerrar(f, sesion);
		long financiadorObligacion = ((Number) obligacionesDe(sesion).get(0).get("id")).longValue();

		List<ObligacionView> elegibles = presentacionService.elegibles(
				f.administrativo(), f.consultorioId(), f.financiador(),
				hoy().minusDays(1), hoy().plusDays(1), 50, 0);

		assertThat(elegibles).singleElement().satisfies(o -> {
			assertThat(o.id()).isEqualTo(financiadorObligacion);
			assertThat(o.concepto()).isEqualTo("FINANCIADOR");
			assertThat(o.importeOriginal()).isEqualByComparingTo("10500.00");
			assertThat(o.convenio().convenioCodigo()).isEqualTo(f.convenioCodigo());
			assertThat(o.convenio().requeriaOrden()).isTrue();
			assertThat(o.convenio().credencialVencida()).isTrue();
		});

		// Y llega de punta a punta: lote armado con ella, confirmado.
		PresentacionView borrador = presentacionService.crear(f.administrativo(), f.consultorioId(),
				new PresentacionCommands.Alta(f.financiador(), hoy().minusDays(30),
						hoy().plusDays(30), "ARS", List.of(financiadorObligacion)));
		PresentacionView confirmada = presentacionService.confirmar(
				f.administrativo(), f.consultorioId(), borrador.id());
		assertThat(confirmada.estado()).isEqualTo(EstadoPresentacion.PRESENTADA);
		assertThat(confirmada.totalPresentado()).isEqualByComparingTo("10500.00");

		// Ya presentada, deja la bandeja (RN-M21-003).
		assertThat(presentacionService.elegibles(f.administrativo(), f.consultorioId(),
				f.financiador(), null, null, 50, 0)).isEmpty();
	}

	@Test
	@DisplayName("el reporte de financiadores suma lo prestado y apaga la advertencia")
	void aparece_en_el_reporte_de_financiadores() {
		Fixture f = crearFixture(true);
		long sesion = insertarSesion(f);
		tratar(f, sesion, f.kine());

		AporteDeReporte antes = reporte(f);
		assertThat(indicador(antes, "prestado")).isEqualByComparingTo("0");
		assertThat(antes.advertencias()).extracting(a -> a.codigo())
				.contains("sin-devengado-de-financiador");

		cerrar(f, sesion);

		AporteDeReporte despues = reporte(f);
		assertThat(indicador(despues, "prestado"))
				.as("solo la parte del financiador; el coseguro es del paciente")
				.isEqualByComparingTo("10500.00");
		assertThat(despues.advertencias()).isEmpty();
		assertThat(despues.filas()).singleElement().satisfies(fila ->
				assertThat(fila.celdas()).startsWith(String.valueOf(f.financiador()), "10500.00"));
	}

	// =================================================================================
	// Tenant y esquema
	// =================================================================================

	@Test
	@DisplayName("otro tenant no ve la deuda del financiador ajeno ni la suma en su reporte")
	void aislamiento_de_tenant() {
		Fixture a = crearFixture(true);
		Fixture b = crearFixture(true);
		long sesion = insertarSesion(a);
		tratar(a, sesion, a.kine());
		cerrar(a, sesion);

		assertThatThrownBy(() -> presentacionService.elegibles(
				b.administrativo(), b.consultorioId(), a.financiador(), null, null, 50, 0))
				.isInstanceOf(FinanciadorNoAccesibleException.class);
		assertThat(presentacionService.elegibles(
				b.administrativo(), b.consultorioId(), b.financiador(), null, null, 50, 0))
				.isEmpty();
		assertThat(indicador(reporte(b), "prestado")).isEqualByComparingTo("0");
	}

	@Test
	@DisplayName("V77: la base rechaza una deuda de financiador sin snapshot de convenio")
	void v77_rechaza_financiador_sin_snapshot() {
		Fixture f = crearFixture(true);
		long sesion = insertarSesion(f);

		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO obligacion (organization_id, consultorio_id, sesion_id, persona_id,
				                        responsable, financiador_id, concepto, importe_original,
				                        saldo, moneda, estado, oferta_id, snapshot_nombre,
				                        snapshot_precio, devengada_en, version, created_at,
				                        updated_at)
				VALUES (?, ?, ?, ?, 'FINANCIADOR', ?, 'FINANCIADOR', 100.00, 100.00, 'ARS',
				        'PENDIENTE', ?, 'Sesion', 100.00, UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6),
				        UTC_TIMESTAMP(6))
				""", f.organizationId(), f.consultorioId(), sesion, f.personaId(),
				f.financiador(), f.ofertaId()))
				// Un CHECK violado (error 3819 de MySQL) llega como UncategorizedSQLException, no como
				// DataIntegrityViolationException: lo que identifica la regla es el nombre.
				.isInstanceOf(DataAccessException.class)
				.hasMessageContaining("ck_obligacion_snapshot_convenio_coherente");
	}

	// =================================================================================
	// Operaciones
	// =================================================================================

	private SesionView cerrar(Fixture f, long sesionId) {
		long version = sesionService.ver(f.profesional(), f.consultorioId(), sesionId).version();
		return cerrarConVersion(f, sesionId, version);
	}

	private SesionView cerrarConVersion(Fixture f, long sesionId, long version) {
		return sesionService.cerrar(f.profesional(), f.consultorioId(), sesionId,
				new CierreDeSesion(Asistencia.PRESENTE, "Terapia manual", null, null, null, null),
				version);
	}

	private List<Map<String, Object>> obligacionesDe(long sesionId) {
		return jdbc.queryForList(
				"SELECT * FROM obligacion WHERE sesion_id = ? ORDER BY id", sesionId);
	}

	private AporteDeReporte reporte(Fixture f) {
		ZoneId zona = ZoneId.of(ZONA);
		LocalDate desde = hoy().minusDays(1);
		LocalDate hasta = hoy().plusDays(1);
		return financiadoresEnElReporte.aportar(new ConsultaDeReporte(
				f.organizationId(), f.consultorioId(), desde, hasta, zona,
				desde.atStartOfDay(zona).toInstant(), hasta.plusDays(1).atStartOfDay(zona).toInstant(),
				100));
	}

	private static BigDecimal indicador(AporteDeReporte aporte, String clave) {
		return aporte.indicadores().stream()
				.filter(i -> i.clave().equals(clave))
				.map(IndicadorDeReporte::valor)
				.findFirst()
				.orElseThrow();
	}

	private static boolean bool(Object valor) {
		return valor instanceof Boolean b ? b : ((Number) valor).intValue() == 1;
	}

	private static LocalDate hoy() {
		return LocalDate.now(ZoneId.of(ZONA));
	}

	// =================================================================================
	// Fixture — todo sintetico
	// =================================================================================

	private record Fixture(
			long organizationId,
			long consultorioId,
			long profesionalCuentaId,
			long profesionalMembershipId,
			long adminCuentaId,
			long personaId,
			long historiaClinicaId,
			long ofertaId,
			long financiador,
			long plan,
			long cobertura,
			long kine,
			long convenio,
			String convenioCodigo,
			long arancel) {

		com.akine.encounter.application.OperatingActor profesional() {
			return new com.akine.encounter.application.OperatingActor(
					profesionalCuentaId, false, organizationId, consultorioId);
		}

		OperatingActor administrativo() {
			return new OperatingActor(adminCuentaId, false, organizationId, consultorioId);
		}
	}

	private Fixture crearFixture(boolean admiteObraSocial) {
		String s = UUID.randomUUID().toString().substring(0, 10);

		long org = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + s, "f4-it-" + s, ZONA);
		long sede = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, "Sede " + s, ZONA);

		long profesional = cuenta("f4-pro-" + s);
		long membership = membership(org, sede, profesional, "PROFESIONAL");
		long admin = cuenta("f4-adm-" + s);
		// cobro:register: leer la cuenta corriente y operar presentaciones.
		membership(org, sede, admin, "ADMINISTRATIVO");

		String apellido = "Paciente" + s;
		long persona = insertar("""
				INSERT INTO persona (organization_id, apellido, nombre, apellido_clave, nombre_clave,
				                     active, version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', ?, 'SINTETICO', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, apellido, apellido.toUpperCase());
		jdbc.update("""
				INSERT INTO perfil_paciente (organization_id, persona_id, activado_en, activado_por,
				                             active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, admin);
		long historia = insertar("""
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, UTC_TIMESTAMP(6), ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, profesional);

		long servicio = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "F4-" + s.toUpperCase(), "Servicio " + s);
		long oferta = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 8500.00, 'ARS', ?, 0, 0, 1, 0,
				        DATE_SUB(CURDATE(), INTERVAL 5 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, servicio, "Oferta " + s, admiteObraSocial ? 1 : 0);

		long financiador = insertar("""
				INSERT INTO financiador (organization_id, codigo, nombre, tipo, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 'OBRA_SOCIAL', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, "OS-" + s, "Financiador " + s);
		long plan = insertar("""
				INSERT INTO plan_cobertura (organization_id, financiador_id, codigo, nombre,
				                            vigencia_desde, requiere_autorizacion,
				                            requiere_credencial, active, version,
				                            created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01', 0, 1, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, financiador, "P-" + s, "Plan " + s);
		// La credencial vencio ayer: B-2 la informa como alerta y no excluye la cobertura.
		long cobertura = insertar("""
				INSERT INTO cobertura_paciente (
				        organization_id, persona_id, tipo,
				        financiador_id, financiador_codigo, financiador_nombre, financiador_tipo,
				        plan_id, plan_codigo, plan_nombre,
				        requeria_autorizacion, requeria_credencial, referencia_capturada_el,
				        numero_afiliado, credencial_vigencia_hasta, vigencia_desde, principal,
				        active, version, created_at, updated_at)
				VALUES (?, ?, 'FINANCIADA', ?, ?, ?, 'OBRA_SOCIAL', ?, ?, ?, 0, 1, UTC_TIMESTAMP(6),
				        ?, DATE_SUB(CURDATE(), INTERVAL 1 DAY), '2020-01-01', 1, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, persona, financiador, "OS-" + s, "Financiador " + s, plan, "P-" + s,
				"Plan " + s, "AF-" + s);

		long especialidad = insertar("""
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active,
				                          version, created_at, updated_at)
				VALUES (?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, "E-" + s, "Especialidad " + s);
		long kine = insertar("""
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, '2020-01-01 00:00:00.000000', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, especialidad, "K-" + s, "Kinesiologia " + s);
		jdbc.update("""
				INSERT INTO oferta_practica (organization_id, consultorio_id, oferta_id, practica_id,
				                             principal, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 1, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, oferta, kine);

		String codigo = "CONV-" + s;
		long convenio = insertar("""
				INSERT INTO convenio (organization_id, consultorio_id, financiador_id, plan_id,
				                      codigo, nombre, modalidad, vigencia_desde, moneda,
				                      requiere_orden, requiere_autorizacion, requiere_credencial,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, ?, 'POR_PRESTACION', '2020-01-01', 'ARS', 1, 0, 1, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, financiador, plan, codigo, "Convenio " + s);
		long arancel = insertar("""
				INSERT INTO convenio_arancel (organization_id, consultorio_id, convenio_id,
				                              practica_id, importe_total, importe_financiador,
				                              coseguro, moneda, vigencia_desde, active, version,
				                              created_at, updated_at)
				VALUES (?, ?, ?, ?, 12000.00, 10500.00, 1500.00, 'ARS', '2020-01-01', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, convenio, kine);

		return new Fixture(org, sede, profesional, membership, admin, persona, historia, oferta,
				financiador, plan, cobertura, kine, convenio, codigo, arancel);
	}

	private long cuenta(String prefijo) {
		String email = prefijo + "@ejemplo.test";
		return insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetico', 'DePrueba', 'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
	}

	private long membership(long org, long sede, long cuenta, String rol) {
		return insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, cuenta, rol);
	}

	private long insertarSesion(Fixture f) {
		return insertar("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'BORRADOR', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", f.organizationId(), f.consultorioId(), f.historiaClinicaId(), f.ofertaId(),
				f.profesionalMembershipId(), f.profesionalCuentaId());
	}

	private void tratar(Fixture f, long sesionId, long practicaId) {
		Integer orden = jdbc.queryForObject(
				"SELECT COALESCE(MAX(orden), 0) + 1 FROM tratamiento_realizado WHERE sesion_id = ?",
				Integer.class, sesionId);
		jdbc.update("""
				INSERT INTO tratamiento_realizado (organization_id, consultorio_id, sesion_id, orden,
				        practica_id, practica_codigo, practica_nombre, profesional_membership_id,
				        registrado_en, registrado_por_cuenta_id, active, version,
				        created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'COD', 'Practica sintetica', ?, UTC_TIMESTAMP(6), ?, 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", f.organizationId(), f.consultorioId(), sesionId, orden, practicaId,
				f.profesionalMembershipId(), f.profesionalCuentaId());
	}

	private long insertar(String insert, Object... args) {
		jdbc.update(insert, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
