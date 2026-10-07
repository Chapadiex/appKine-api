package com.akine.reporting;

import com.akine.TestcontainersConfiguration;
import com.akine.organization.domain.exception.PermissionDeniedException;
import com.akine.reporting.application.OperatingActor;
import com.akine.reporting.application.ReporteService;
import com.akine.reporting.application.ReporteView;
import com.akine.reporting.domain.exception.ConsultorioNoAccesibleException;
import com.akine.reporting.spi.AdvertenciaDeReporte;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * {@code reporte:read} segun la matriz (AKINE-G-1, DP-15), contra MySQL real.
 *
 * <h2>Por que es IT y no unitario</h2>
 *
 * <p>Lo que se prueba son tres cosas que un mock no puede mentir: que el evaluador de permisos,
 * leyendo memberships reales, conceda {@code reporte:read} a quien la matriz dice y con el alcance
 * que dice; que las consultas JPQL y nativas de turnos, sesiones y casos <b>recorten de verdad</b>
 * por profesional —un {@code IN} mal ligado o un {@code :recortar} que MySQL compare como texto
 * devuelve el total y el test unitario no se entera—; y que el uso de soporte quede en
 * {@code audit_event}, no en un mock.
 *
 * <p>Entra por el {@link ReporteService} con un {@link OperatingActor} armado como lo arma el
 * controller. El mapeo de las excepciones a HTTP —{@code PermissionDeniedException} a 403,
 * {@code ConsultorioNoAccesibleException} a 404— ya lo prueban los handlers de cada modulo.
 *
 * <p>Datos: una sede con dos profesionales. A tiene 2 turnos, 1 sesion cerrada y 2 casos (uno lo
 * trato y dejo el equipo); B tiene
 * 3 turnos, 2 sesiones cerradas y 2 casos. Todo sintetico.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
class ReporteReadIT {

	private static final String ZONA = "America/Argentina/Cordoba";

	@Autowired private ReporteService reportes;
	@Autowired private JdbcTemplate jdbc;

	private Centro centro;
	private LocalDate desde;
	private LocalDate hasta;

	@BeforeEach
	void preparar() {
		centro = crearCentro();
		LocalDate hoy = LocalDate.now(ZoneId.of(ZONA));
		desde = hoy.minusDays(5);
		hasta = hoy.plusDays(5);
	}

	// =================================================================================
	// "Si" — ORG_ADMIN y CONSULTORIO_ADMIN
	// =================================================================================

	@Test
	@DisplayName("ORG_ADMIN ve el reporte de toda la sede; lo clinico se omite por permiso de seccion")
	void el_org_admin_ve_toda_la_sede() {
		ReporteView vista = generar(centro.orgAdmin(), ReporteCode.OPERATIVO);

		assertThat(valor(vista, "turnos", "turnos-totales")).isEqualByComparingTo("5");
		// Tiene cobro:register: la economia de la sede le llega.
		assertThat(secciones(vista)).contains("turnos", "economia");
		assertThat(vista.omitidas())
				.extracting(ReporteView.SeccionOmitida::seccion,
						ReporteView.SeccionOmitida::permisoRequerido)
				.containsExactly(tuple("sesiones", "sesion:register"));
		assertThat(codigosDeAdvertencia(vista)).doesNotContain("alcance-actividad-propia");
	}

	@Test
	@DisplayName("CONSULTORIO_ADMIN ve el reporte de su sede entera")
	void el_admin_de_sede_ve_su_sede() {
		ReporteView vista = generar(centro.consultorioAdmin(), ReporteCode.TURNOS);

		assertThat(valor(vista, "turnos", "turnos-totales")).isEqualByComparingTo("5");
		assertThat(codigosDeAdvertencia(vista)).doesNotContain("alcance-actividad-propia");
	}

	// =================================================================================
	// "Limitado" — ADMINISTRATIVO
	// =================================================================================

	@Test
	@DisplayName("ADMINISTRATIVO ve operativos y caja de la sede, sin lo clinico, y lo declara")
	void el_administrativo_no_ve_lo_clinico() {
		ReporteView operativo = generar(centro.administrativo(), ReporteCode.OPERATIVO);
		assertThat(valor(operativo, "turnos", "turnos-totales"))
				.as("la agenda de la sede entera: su Limitado no es por actividad")
				.isEqualByComparingTo("5");
		assertThat(secciones(operativo)).contains("turnos", "economia").doesNotContain("sesiones");

		ReporteView clinico = generar(centro.administrativo(), ReporteCode.CLINICO);
		assertThat(clinico.secciones()).isEmpty();
		assertThat(clinico.omitidas())
				.extracting(ReporteView.SeccionOmitida::seccion,
						ReporteView.SeccionOmitida::permisoRequerido)
				.containsExactlyInAnyOrder(
						tuple("sesiones", "sesion:register"),
						tuple("casos", "hc:read"));
	}

	// =================================================================================
	// "Limitado" — PROFESIONAL: solo su propia actividad
	// =================================================================================

	@Test
	@DisplayName("Cada PROFESIONAL ve solo sus turnos, sus sesiones y sus casos")
	void cada_profesional_ve_lo_suyo() {
		ReporteView operativoA = generar(centro.profesionalA(), ReporteCode.OPERATIVO);
		ReporteView operativoB = generar(centro.profesionalB(), ReporteCode.OPERATIVO);

		assertThat(valor(operativoA, "turnos", "turnos-totales")).isEqualByComparingTo("2");
		assertThat(valor(operativoB, "turnos", "turnos-totales")).isEqualByComparingTo("3");
		assertThat(valor(operativoA, "sesiones", "sesiones-cerradas")).isEqualByComparingTo("1");
		assertThat(valor(operativoB, "sesiones", "sesiones-cerradas")).isEqualByComparingTo("2");

		// Lo economico ni llega: no tiene cobro:register.
		assertThat(secciones(operativoA)).containsExactlyInAnyOrder("turnos", "sesiones");
		assertThat(operativoA.omitidas()).extracting(ReporteView.SeccionOmitida::seccion)
				.contains("economia");
		assertThat(codigosDeAdvertencia(operativoA)).contains("alcance-actividad-propia");

		ReporteView clinicoA = generar(centro.profesionalA(), ReporteCode.CLINICO);
		ReporteView clinicoB = generar(centro.profesionalB(), ReporteCode.CLINICO);

		assertThat(valor(clinicoA, "casos", "casos-abiertos-en-el-periodo"))
				.as("el suyo y el que trato antes de dejar el equipo")
				.isEqualByComparingTo("2");
		assertThat(valor(clinicoB, "casos", "casos-abiertos-en-el-periodo"))
				.isEqualByComparingTo("2");
		assertThat(valor(clinicoA, "casos", "casos-activos-al-corte")).isEqualByComparingTo("2");
		assertThat(valor(clinicoB, "casos", "casos-activos-al-corte")).isEqualByComparingTo("2");

		// El detalle tambien: una fila por caso con sesiones, solo de las suyas.
		assertThat(detalle(clinicoB, "sesiones")).hasSize(1).first()
				.satisfies(fila -> assertThat(fila).containsExactly("sin caso", "2"));
	}

	@Test
	@DisplayName("El reporte limitado de un profesional queda auditado con su alcance")
	void el_reporte_clinico_limitado_se_audita_con_su_alcance() {
		generar(centro.profesionalA(), ReporteCode.CLINICO);

		List<Map<String, Object>> filas = jdbc.queryForList("""
				SELECT details FROM audit_event
				 WHERE event_type = 'REPORTE_CONSULTADO'
				   AND organization_id = ? AND actor_account_id = ?
				""", centro.organizationId(), centro.profesionalA().accountId());
		assertThat(filas).singleElement()
				.satisfies(fila -> assertThat(String.valueOf(fila.get("details")))
						.contains("ACTIVIDAD_PROPIA"));
	}

	// =================================================================================
	// "No" y aislamiento
	// =================================================================================

	@Test
	@DisplayName("PACIENTE no tiene reporte:read: 403")
	void el_paciente_no_ve_reportes() {
		assertThatThrownBy(() -> generar(centro.paciente(), ReporteCode.OPERATIVO))
				.isInstanceOf(PermissionDeniedException.class);
	}

	@Test
	@DisplayName("La sede de otro tenant es 404, aun para su ORG_ADMIN con reporte:read")
	void otro_tenant_es_404() {
		Centro otro = crearCentro();

		assertThatThrownBy(() -> reportes.generar(
				otro.orgAdmin(), centro.consultorioId(), ReporteCode.OPERATIVO, desde, hasta))
				.isInstanceOf(ConsultorioNoAccesibleException.class);
		assertThatThrownBy(() -> reportes.catalogo(otro.orgAdmin(), centro.consultorioId()))
				.isInstanceOf(ConsultorioNoAccesibleException.class);
	}

	// =================================================================================
	// "Global" — PLATFORM_ADMIN, leido como SOPORTE (matriz §9.7)
	// =================================================================================

	@Test
	@DisplayName("PLATFORM_ADMIN sin soporte: 403. Con soporte: lee y deja SUPPORT_ACCESS_USED")
	void el_admin_de_plataforma_lee_solo_con_soporte() {
		long cuenta = crearCuenta("plataforma");
		jdbc.update("""
				INSERT INTO platform_role (account_id, role_code, granted_by_account_id, reason,
				                           valid_from, active, version, created_at, updated_at)
				VALUES (?, 'PLATFORM_ADMIN', NULL, 'Fixture sintetico de test de integracion',
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", cuenta);
		OperatingActor plataforma =
				new OperatingActor(cuenta, true, centro.organizationId(), centro.consultorioId());

		assertThatThrownBy(() -> generar(plataforma, ReporteCode.TURNOS))
				.isInstanceOf(PermissionDeniedException.class);

		jdbc.update("""
				INSERT INTO support_access (organization_id, account_id, reason,
				                            granted_by_account_id, valid_from, valid_until,
				                            active, version, created_at, updated_at)
				VALUES (?, ?, 'Incidente sintetico de test de integracion', ?,
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE),
				        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", centro.organizationId(), cuenta, cuenta);

		ReporteView vista = generar(plataforma, ReporteCode.OPERATIVO);
		assertThat(valor(vista, "turnos", "turnos-totales")).isEqualByComparingTo("5");
		// Soporte mira la agenda; lo clinico y lo economico no son suyos.
		assertThat(vista.omitidas()).extracting(ReporteView.SeccionOmitida::seccion)
				.contains("sesiones", "economia");

		assertThat(jdbc.queryForList("""
				SELECT details FROM audit_event
				 WHERE event_type = 'SUPPORT_ACCESS_USED'
				   AND organization_id = ? AND actor_account_id = ?
				""", centro.organizationId(), cuenta))
				.as("cada lectura amparada por soporte deja su fila (matriz §7)")
				.singleElement()
				.satisfies(fila -> assertThat(String.valueOf(fila.get("details")))
						.contains("reporte:read"));
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private ReporteView generar(OperatingActor actor, ReporteCode reporte) {
		return reportes.generar(actor, centro.consultorioId(), reporte, desde, hasta);
	}

	private static List<String> secciones(ReporteView vista) {
		return vista.secciones().stream().map(AporteDeReporte::seccion).toList();
	}

	private static List<String> codigosDeAdvertencia(ReporteView vista) {
		return vista.advertencias().stream().map(AdvertenciaDeReporte::codigo).toList();
	}

	private static AporteDeReporte seccion(ReporteView vista, String seccion) {
		return vista.secciones().stream()
				.filter(aporte -> aporte.seccion().equals(seccion))
				.findFirst()
				.orElseThrow(() -> new AssertionError("El reporte no trae la seccion " + seccion
						+ "; omitidas: " + vista.omitidas()));
	}

	private static BigDecimal valor(ReporteView vista, String seccion, String clave) {
		return seccion(vista, seccion).indicadores().stream()
				.filter(indicador -> indicador.clave().equals(clave))
				.map(IndicadorDeReporte::valor)
				.findFirst()
				.orElseThrow(() -> new AssertionError("No hay indicador " + clave));
	}

	private static List<List<String>> detalle(ReporteView vista, String seccion) {
		return seccion(vista, seccion).filas().stream().map(fila -> fila.celdas()).toList();
	}

	// =================================================================================
	// Fixture — insercion directa y sintetica (AGENT.md §10)
	// =================================================================================

	private record Centro(
			long organizationId, long consultorioId,
			OperatingActor orgAdmin, OperatingActor consultorioAdmin,
			OperatingActor administrativo, OperatingActor profesionalA,
			OperatingActor profesionalB, OperatingActor paciente) {
	}

	private Centro crearCentro() {
		String sufijo = UUID.randomUUID().toString().substring(0, 12);

		long organizationId = insertar("""
				INSERT INTO organization (name, slug, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "Centro " + sufijo, "reporte-it-" + sufijo, ZONA);
		long consultorioId = insertar("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version,
				                         created_at, updated_at)
				VALUES (?, ?, ?, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, "Sede " + sufijo, ZONA);

		OperatingActor orgAdmin = miembro(organizationId, consultorioId, null, "ORG_ADMIN");
		OperatingActor consultorioAdmin =
				miembro(organizationId, consultorioId, consultorioId, "CONSULTORIO_ADMIN");
		OperatingActor administrativo =
				miembro(organizationId, consultorioId, consultorioId, "ADMINISTRATIVO");
		OperatingActor paciente = miembro(organizationId, consultorioId, consultorioId, "PACIENTE");

		long cuentaA = crearCuenta("prof-a");
		long membershipA = membership(organizationId, consultorioId, cuentaA, "PROFESIONAL");
		long cuentaB = crearCuenta("prof-b");
		long membershipB = membership(organizationId, consultorioId, cuentaB, "PROFESIONAL");

		long servicioId = insertar("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "REP-" + sufijo.toUpperCase(), "Servicio " + sufijo);
		long ofertaId = insertar("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 60, 1, 1000.00, 'ARS', 0, 0, 0, 1, 0,
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
				""", organizationId, personaId, cuentaA);

		// Turnos: 2 de A, 3 de B, en horas distintas de manana.
		int hora = 24;
		for (int i = 0; i < 2; i++) {
			turno(organizationId, consultorioId, ofertaId, personaId, membershipA, cuentaA, hora++);
		}
		for (int i = 0; i < 3; i++) {
			turno(organizationId, consultorioId, ofertaId, personaId, membershipB, cuentaB, hora++);
		}

		// Sesiones cerradas: 1 de A, 2 de B.
		int numero = 1;
		sesionCerrada(organizationId, consultorioId, historiaId, ofertaId, membershipA, cuentaA,
				numero++);
		sesionCerrada(organizationId, consultorioId, historiaId, ofertaId, membershipB, cuentaB,
				numero++);
		sesionCerrada(organizationId, consultorioId, historiaId, ofertaId, membershipB, cuentaB,
				numero);

		// Casos: uno solo de A, uno solo de B, y un tercero que responde B y en el que A trato y
		// ya dejo el equipo. "Le fue asignado" incluye a quien lo trato: caso_profesional no borra
		// la fila justamente por eso (V47, punto 5). A ve 2 casos, B ve 2.
		long caso1 = caso(organizationId, historiaId, ofertaId, consultorioId, 1, cuentaA);
		equipo(organizationId, caso1, membershipA, "RESPONSABLE", false);
		long caso2 = caso(organizationId, historiaId, ofertaId, consultorioId, 2, cuentaB);
		equipo(organizationId, caso2, membershipB, "RESPONSABLE", false);
		long caso3 = caso(organizationId, historiaId, ofertaId, consultorioId, 3, cuentaB);
		equipo(organizationId, caso3, membershipB, "RESPONSABLE", false);
		equipo(organizationId, caso3, membershipA, "TRATANTE", true);

		return new Centro(organizationId, consultorioId, orgAdmin, consultorioAdmin,
				administrativo,
				new OperatingActor(cuentaA, false, organizationId, consultorioId),
				new OperatingActor(cuentaB, false, organizationId, consultorioId),
				paciente);
	}

	private OperatingActor miembro(
			long organizationId, long consultorioDelContexto, Long consultorioDeLaMembership,
			String rol) {

		long cuenta = crearCuenta(rol.toLowerCase());
		insertar("""
				INSERT INTO membership (organization_id, consultorio_id, account_id, role_code,
				                        is_founder, valid_from, estado, active, version,
				                        created_at, updated_at)
				VALUES (?, ?, ?, ?, 0, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 5 YEAR),
				        'ACTIVA', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioDeLaMembership, cuenta, rol);
		return new OperatingActor(cuenta, false, organizationId, consultorioDelContexto);
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
		String email = "reporte-it-" + etiqueta + "-" + UUID.randomUUID() + "@ejemplo.test";
		return insertar("""
				INSERT INTO cuenta (email, email_normalizado, nombre, apellido, estado, active,
				                    version, created_at, updated_at)
				VALUES (?, ?, 'Sintetica', 'DePrueba', 'ACTIVA', 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", email, email);
	}

	private void turno(long organizationId, long consultorioId, long ofertaId, long personaId,
			long membershipId, long cuentaId, int horasDesdeAhora) {

		jdbc.update("""
				INSERT INTO turno (organization_id, consultorio_id, oferta_id, persona_id,
				                   profesional_membership_id, inicio, fin, estado,
				                   reservado_por_cuenta_id, reservado_en, version,
				                   created_at, updated_at)
				VALUES (?, ?, ?, ?, ?,
				        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? HOUR),
				        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL ? MINUTE),
				        'RESERVADO', ?, UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, ofertaId, personaId, membershipId,
				horasDesdeAhora, horasDesdeAhora * 60 + 45, cuentaId);
	}

	private void sesionCerrada(long organizationId, long consultorioId, long historiaId,
			long ofertaId, long membershipId, long cuentaId, int numeroSesion) {

		jdbc.update("""
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, numero_sesion, iniciada_en,
				                    iniciada_por_cuenta_id, cerrada_en, cerrada_por_cuenta_id,
				                    ultimo_numero_version, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'CERRADA', ?, UTC_TIMESTAMP(6), ?, UTC_TIMESTAMP(6), ?,
				        1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, consultorioId, historiaId, ofertaId, membershipId,
				numeroSesion, cuentaId, cuentaId);
	}

	private long caso(long organizationId, long historiaId, long ofertaId, long consultorioId,
			int numero, long abiertoPor) {

		return insertar("""
				INSERT INTO caso_clinico (organization_id, historia_clinica_id, numero_caso,
				                          oferta_id, oferta_consultorio_id, diagnostico_presuntivo,
				                          estado, abierto_en, abierto_por, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'Diagnostico sintetico', 'ACTIVO', UTC_TIMESTAMP(6), ?, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, historiaId, numero, ofertaId, consultorioId, abiertoPor);
	}

	private void equipo(
			long organizationId, long casoId, long membershipId, String rol, boolean yaSalio) {

		jdbc.update("""
				INSERT INTO caso_profesional (organization_id, caso_id, profesional_membership_id,
				                              rol, desde, hasta, created_at, updated_at)
				VALUES (?, ?, ?, ?, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 2 DAY),
				        IF(?, DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 DAY), NULL),
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", organizationId, casoId, membershipId, rol, yaSalio);
	}

	private long insertar(String sql, Object... args) {
		jdbc.update(sql, args);
		return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
	}
}
