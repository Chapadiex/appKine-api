package com.akine.assistant;

import com.akine.TestcontainersConfiguration;
import com.akine.activity.application.InscribirCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.organization.application.MembershipService;
import com.akine.organization.spi.DirectMembershipCommand;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.PerfilPacienteService;
import com.akine.person.application.PersonaAltaCommand;
import com.akine.person.application.PersonaService;
import com.akine.person.domain.TipoDocumento;
import com.akine.platform.infrastructure.modelo.ModeloDeLenguajeDePrueba;
import com.akine.platform.spi.modelo.ModeloDeLenguaje;
import com.akine.testsupport.InsertarConId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Las acciones del asistente de punta a punta (docs/diseno/AKINE-asistente-acciones.md): HTTP real,
 * login y contexto por el camino real, PostgreSQL, y cada accion llamando al servicio de aplicacion
 * de su modulo. Solo el modelo es de prueba: devuelve el JSON que cada caso le fija.
 *
 * <p>Cada caso mira <b>la base</b> despues de confirmar: un 200 no prueba que el dato quedo.
 *
 * <p>La organizacion, la sede y las cuentas nacen por la API; las personas, por sus servicios. La
 * oferta, la practica, la clase, los turnos, la deuda y la caja se insertan por JDBC, igual que en
 * los ITs de {@code billing}, {@code activity} y {@code encounter}: llegar hasta ahi por los
 * servicios exige disponibilidad, agenda y cierre, que tienen sus propios tests.
 */
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {
				"akine.security.rate-limit.enabled=false",
				"akine.asistente.enabled=true",
				"akine.asistente.acciones.enabled=true",
				"akine.asistente.modelo.tipo=prueba",
				"akine.asistente.agente.proveedor=prueba"})
@ActiveProfiles("local")
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AsistenteAccionesIT {

	private static final String PASSWORD = "Sintetica-Akine-2026";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@LocalServerPort int puerto;
	@Autowired JdbcTemplate jdbc;
	@Autowired MembershipService membershipService;
	@Autowired PersonaService personas;
	@Autowired PerfilPacienteService perfiles;
	@Autowired InscripcionService inscripciones;
	@Autowired ModeloDeLenguaje modeloDeLenguaje;

	private final HttpClient http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(10))
			.build();

	private ModeloDeLenguajeDePrueba modelo;
	/** ORG_ADMIN del centro donde pasa casi todo, con la caja abierta. */
	private Sesion admin;
	private Sesion profesional;
	private long profesionalMembershipId;
	private Sesion administrativo;
	/** Otro centro, sin caja abierta. */
	private Sesion otroCentro;
	private long ofertaId;
	private long practicaId;
	private String practica;

	@BeforeAll
	void sembrar() throws Exception {
		modelo = (ModeloDeLenguajeDePrueba) modeloDeLenguaje;
		admin = altaCompleta("acciones-a");
		otroCentro = altaCompleta("acciones-b");
		profesional = conRolEn(admin, "acciones-pro", "PROFESIONAL");
		administrativo = conRolEn(admin, "acciones-adm", "ADMINISTRATIVO");
		profesionalMembershipId = jdbc.queryForObject(
				"SELECT id FROM membership WHERE organization_id = ? AND account_id = ?", Long.class,
				admin.organizationId(), profesional.cuentaId());

		String s = sufijo();
		long servicioId = InsertarConId.insertar(jdbc, """
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'INDIVIDUAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", "ACC-" + s.toUpperCase(Locale.ROOT), "Servicio " + s);
		ofertaId = InsertarConId.insertar(jdbc, """
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, precio_base, moneda, admite_obra_social,
				        requiere_caso_clinico, genera_registro_clinico, requiere_profesional,
				        requiere_espacio, vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'INDIVIDUAL', 30, 1, 8500.00, 'ARS', false, false, false, true, false,
				        CAST(CURRENT_DATE - INTERVAL '5 years' AS date), true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", admin.organizationId(), admin.consultorioId(), servicioId, "Kinesiologia " + s);

		long especialidadId = InsertarConId.insertar(jdbc, """
				INSERT INTO especialidad (organization_id, codigo, name, valid_from, active, version,
				                          created_at, updated_at)
				VALUES (?, ?, ?, (CURRENT_TIMESTAMP - INTERVAL '5 years'), true, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", admin.organizationId(), "ESP-" + s, "Especialidad " + s);
		practica = "Ondas" + s;
		practicaId = InsertarConId.insertar(jdbc, """
				INSERT INTO practica (organization_id, especialidad_id, codigo, name, valid_from, active,
				                      version, created_at, updated_at)
				VALUES (?, ?, ?, ?, (CURRENT_TIMESTAMP - INTERVAL '5 years'), true, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", admin.organizationId(), especialidadId, "PR-" + s, practica);

		// Caja abierta en cero en el centro A; el centro B queda sin caja.
		jdbc.update("""
				INSERT INTO jornada_caja (organization_id, consultorio_id, fecha_negocio, moneda,
				                          estado, saldo_inicial, saldo_arqueo, abierta_en,
				                          abierta_por_cuenta_id, created_at, updated_at)
				VALUES (?, ?, CAST(? AS date), 'ARS', 'ABIERTA', 0.00, 0.00, CURRENT_TIMESTAMP, ?,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", admin.organizationId(), admin.consultorioId(), LocalDate.now(zonaDeLaSede()).toString(),
				admin.cuentaId());
	}

	@AfterEach
	void modeloPorDefecto() {
		modelo.reiniciar();
	}

	// ============================================================================ a. pago

	@Test
	void un_pago_se_propone_sin_grabar_y_al_confirmar_cobra_una_sola_vez() throws Exception {
		String apellido = "Pagador" + sufijo();
		long personaId = paciente(admin, apellido, "Juan", null, null);
		long obligacionId = deuda(personaId);
		BigDecimal cajaAntes = saldoDeCaja();

		JsonNode r = decir(admin, apellido + " pagó 15000 en efectivo",
				Map.of("accion", "PAGO", "persona", apellido, "monto", "15000"));

		assertThat(r.get("resultado").asString()).as(r.toString()).isEqualTo("PROPUESTA");
		JsonNode accion = r.get("accion");
		assertThat(accion.get("tipo").asString()).isEqualTo("PAGO");
		assertThat(accion.get("bloqueos")).as(accion.toString()).isEmpty();
		JsonNode pago = accion.get("pago");
		assertThat(pago.get("personaId").asLong()).isEqualTo(personaId);
		assertThat(pago.get("total").decimalValue()).isEqualByComparingTo("15000");
		assertThat(pago.get("medio").asString()).isEqualTo("EFECTIVO");
		assertThat(pago.get("cajaAbierta").asBoolean()).isTrue();
		assertThat(pago.get("imputaciones")).hasSize(1);
		assertThat(pago.get("imputaciones").get(0).get("obligacionId").asLong()).isEqualTo(obligacionId);
		assertThat(pago.get("imputaciones").get(0).get("importe").decimalValue()).isEqualByComparingTo("8500");
		assertThat(pago.get("anticipo").decimalValue()).isEqualByComparingTo("6500");
		long accionId = accion.get("id").asLong();

		// Proponer no graba.
		assertThat(cobrosDe(personaId)).isZero();
		assertThat(estadoDeLaAccion(accionId)).isEqualTo("PROPUESTA");

		JsonNode primera = confirmar(admin, admin.consultorioId(), accionId, "pago", pago, 200);

		assertThat(primera.get("estado").asString()).as(primera.toString()).isEqualTo("CONFIRMADA");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cobro WHERE organization_id = ? AND idempotency_key = ?",
				Integer.class, admin.organizationId(), "asistente-accion-" + accionId)).isEqualTo(1);
		Map<String, Object> obligacion = jdbc.queryForMap("SELECT estado, saldo FROM obligacion WHERE id = ?", obligacionId);
		assertThat(obligacion.get("estado")).isEqualTo("PAGADA");
		assertThat((BigDecimal) obligacion.get("saldo")).isEqualByComparingTo("0");
		assertThat(jdbc.queryForObject("SELECT total FROM cobro WHERE idempotency_key = ?", BigDecimal.class,
				"asistente-accion-" + accionId)).isEqualByComparingTo("15000");
		assertThat(saldoDeCaja().subtract(cajaAntes)).as("el efectivo entro al cajon una vez")
				.isEqualByComparingTo("15000");

		// Confirmar otra vez: lo mismo, sin cobrar de nuevo.
		JsonNode segunda = confirmar(admin, admin.consultorioId(), accionId, "pago", pago, 200);

		assertThat(segunda.get("estado").asString()).isEqualTo("CONFIRMADA");
		assertThat(segunda.get("enlace").asString()).isEqualTo(primera.get("enlace").asString());
		assertThat(cobrosDe(personaId)).isEqualTo(1);
		assertThat(saldoDeCaja().subtract(cajaAntes)).isEqualByComparingTo("15000");

		// La fila: confirmada y solo con ids.
		Map<String, Object> fila = jdbc.queryForMap(
				"SELECT estado, referencias::text AS referencias, resultado::text AS resultado, resuelta_en "
						+ "FROM asistente_accion WHERE id = ?", accionId);
		assertThat(fila.get("estado")).isEqualTo("CONFIRMADA");
		assertThat(fila.get("resuelta_en")).isNotNull();
		String guardado = (fila.get("referencias") + " " + fila.get("resultado")).toLowerCase(Locale.ROOT);
		assertThat(guardado).contains("cobroid").contains(String.valueOf(personaId));
		assertThat(guardado).as("la fila no guarda nombres ni textos")
				.doesNotContain(apellido.toLowerCase(Locale.ROOT)).doesNotContain("juan")
				.doesNotContain("registr").doesNotContain("efectivo");

		// La auditoria tampoco lleva la frase ni el nombre.
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE organization_id = ? "
				+ "AND event_type LIKE 'ASISTENTE_ACCION_%' AND details::text ILIKE ?", Integer.class,
				admin.organizationId(), "%" + apellido + "%")).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE organization_id = ? "
				+ "AND event_type = 'ASISTENTE_ACCION_CONFIRMADA' AND details::text ~ ?", Integer.class,
				admin.organizationId(), "\"accionId\"\\s*:\\s*\"" + accionId + "\"")).isEqualTo(1);
	}

	// ============================================================================ b. caja cerrada

	@Test
	void el_efectivo_con_la_caja_cerrada_bloquea_y_confirmar_igual_no_cobra() throws Exception {
		String apellido = "Sincaja" + sufijo();
		long personaId = paciente(otroCentro, apellido, "Rosa", null, null);

		JsonNode r = decir(otroCentro, apellido + " pagó 4000 en efectivo",
				Map.of("accion", "PAGO", "persona", apellido, "monto", "4000"));

		assertThat(r.get("resultado").asString()).as(r.toString()).isEqualTo("PROPUESTA");
		JsonNode accion = r.get("accion");
		assertThat(accion.get("pago").get("cajaAbierta").asBoolean()).isFalse();
		assertThat(accion.get("bloqueos").toString()).contains("caja");
		long accionId = accion.get("id").asLong();

		JsonNode resultado = confirmar(otroCentro, otroCentro.consultorioId(), accionId, "pago", accion.get("pago"), 200);

		assertThat(resultado.get("estado").asString()).as(resultado.toString()).isEqualTo("RECHAZADA");
		assertThat(resultado.get("texto").asString()).contains("caja");
		assertThat(cobrosDe(personaId)).isZero();
		assertThat(estadoDeLaAccion(accionId)).as("queda abierta para corregir").isEqualTo("PROPUESTA");
	}

	// ============================================================================ c. doble clic

	@Test
	void dos_confirmaciones_a_la_vez_cobran_una_sola_vez() throws Exception {
		String apellido = "Dobleclic" + sufijo();
		long personaId = paciente(admin, apellido, "Juan", null, null);
		long obligacionId = deuda(personaId);

		JsonNode accion = decir(admin, apellido + " pagó 8500 por transferencia",
				Map.of("accion", "PAGO", "persona", apellido, "monto", "8500")).get("accion");
		assertThat(accion).isNotNull();
		assertThat(accion.get("pago").get("medio").asString()).isEqualTo("TRANSFERENCIA");
		long accionId = accion.get("id").asLong();
		String cuerpo = JSON.writeValueAsString(Map.of("pago", accion.get("pago")));
		String ruta = "/api/v1/consultorios/" + admin.consultorioId() + "/asistente/acciones/" + accionId + "/confirmacion";

		int hilos = 2;
		CountDownLatch listos = new CountDownLatch(hilos);
		CountDownLatch salida = new CountDownLatch(1);
		List<HttpResponse<String>> respuestas = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(hilos)) {
			List<Future<HttpResponse<String>>> futuros = new ArrayList<>();
			for (int i = 0; i < hilos; i++) {
				futuros.add(pool.submit(() -> {
					listos.countDown();
					salida.await(10, TimeUnit.SECONDS);
					return post(ruta, admin.token(), cuerpo);
				}));
			}
			assertThat(listos.await(10, TimeUnit.SECONDS)).isTrue();
			salida.countDown();
			for (Future<HttpResponse<String>> futuro : futuros) {
				respuestas.add(futuro.get(60, TimeUnit.SECONDS));
			}
		}

		// Una confirma. La otra recibe el mismo resultado si llego despues, o 409 si llego en el medio.
		long confirmadas = 0;
		for (HttpResponse<String> respuesta : respuestas) {
			assertThat(respuesta.statusCode()).as(respuesta.body()).isIn(200, 409);
			if (respuesta.statusCode() == 200) {
				assertThat(JSON.readTree(respuesta.body()).get("estado").asString()).isEqualTo("CONFIRMADA");
				confirmadas++;
			}
			else {
				assertThat(JSON.readTree(respuesta.body()).get("type").asString()).endsWith("/accion-no-vigente");
			}
		}
		assertThat(confirmadas).isGreaterThanOrEqualTo(1);
		assertThat(cobrosDe(personaId)).as("un solo cobro").isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cobro_imputacion WHERE obligacion_id = ?", Integer.class,
				obligacionId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT saldo FROM obligacion WHERE id = ?", BigDecimal.class, obligacionId))
				.isEqualByComparingTo("0");
		assertThat(estadoDeLaAccion(accionId)).isEqualTo("CONFIRMADA");
	}

	// ============================================================================ d. asistencia

	@Test
	void vinieron_todos_menos_uno_inicia_la_clase_y_graba_presentes_y_ausentes() throws Exception {
		String s = sufijo();
		String ausente = "Faltante" + s;
		long p1 = paciente(admin, "Asistea" + s, "Ana", null, null);
		long p2 = paciente(admin, "Asisteb" + s, "Beto", null, null);
		long p3 = paciente(admin, ausente, "Carla", null, null);
		long claseId = claseDeHoyCon("Yoga" + s, List.of(p1, p2, p3));

		Map<String, Object> entendido = new LinkedHashMap<>();
		entendido.put("accion", "ASISTENCIA_CLASE");
		entendido.put("clase", "Yoga" + s);
		entendido.put("modo", "TODOS_MENOS");
		entendido.put("nombres", List.of(ausente));
		JsonNode r = decir(admin, "a Yoga" + s + " vinieron todos menos " + ausente, entendido);

		assertThat(r.get("resultado").asString()).as(r.toString()).isEqualTo("PROPUESTA");
		JsonNode accion = r.get("accion");
		assertThat(accion.get("tipo").asString()).isEqualTo("ASISTENCIA_CLASE");
		assertThat(accion.get("bloqueos")).as(accion.toString()).isEmpty();
		JsonNode asistencia = accion.get("asistencia");
		assertThat(asistencia.get("claseId").asLong()).isEqualTo(claseId);
		assertThat(asistencia.get("clases").get(0).get("items")).hasSize(3);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asistencia_actividad WHERE clase_id = ?", Integer.class,
				claseId)).as("proponer no graba").isZero();
		assertThat(jdbc.queryForObject("SELECT estado FROM clase_programada WHERE id = ?", String.class, claseId))
				.isEqualTo("PROGRAMADA");

		JsonNode resultado = confirmar(admin, admin.consultorioId(), accion.get("id").asLong(), "asistencia",
				asistencia, 200);

		assertThat(resultado.get("estado").asString()).as(resultado.toString()).isEqualTo("CONFIRMADA");
		assertThat(resultado.get("detalles")).as(resultado.toString()).isEmpty();
		assertThat(jdbc.queryForObject("SELECT estado FROM clase_programada WHERE id = ?", String.class, claseId))
				.isEqualTo("EN_CURSO");
		assertThat(jdbc.queryForList("SELECT persona_id FROM asistencia_actividad WHERE clase_id = ? "
				+ "AND resultado = 'PRESENTE'", Long.class, claseId)).containsExactlyInAnyOrder(p1, p2);
		assertThat(jdbc.queryForList("SELECT persona_id FROM asistencia_actividad WHERE clase_id = ? "
				+ "AND resultado = 'AUSENTE'", Long.class, claseId)).containsExactly(p3);
	}

	// ============================================================================ e. paciente

	@Test
	void el_alta_crea_la_persona_con_perfil_de_paciente_y_su_telefono() throws Exception {
		String apellido = "Diaz" + sufijo();
		Map<String, Object> entendido = new LinkedHashMap<>();
		entendido.put("accion", "PACIENTE_ALTA");
		entendido.put("nombre", "Laura");
		entendido.put("apellido", apellido);
		entendido.put("telefono", "3515551234");
		JsonNode r = decir(admin, "agregá a Laura " + apellido + ", teléfono 351 555 1234", entendido);

		assertThat(r.get("resultado").asString()).as(r.toString()).isEqualTo("PROPUESTA");
		JsonNode accion = r.get("accion");
		assertThat(accion.get("tipo").asString()).isEqualTo("PACIENTE_ALTA");
		assertThat(accion.get("bloqueos")).as(accion.toString()).isEmpty();
		assertThat(accion.get("paciente").get("datos").get("telefono").asString()).isEqualTo("3515551234");
		assertThat(personasCon(apellido)).as("proponer no graba").isZero();

		JsonNode resultado = confirmar(admin, admin.consultorioId(), accion.get("id").asLong(), "paciente",
				accion.get("paciente"), 200);

		assertThat(resultado.get("estado").asString()).as(resultado.toString()).isEqualTo("CONFIRMADA");
		Map<String, Object> persona = jdbc.queryForMap(
				"SELECT id, nombre, telefono, active FROM persona WHERE organization_id = ? AND apellido = ?",
				admin.organizationId(), apellido);
		assertThat(persona.get("nombre")).isEqualTo("Laura");
		assertThat(persona.get("telefono")).isEqualTo("3515551234");
		assertThat(persona.get("active")).isEqualTo(true);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM perfil_paciente WHERE organization_id = ? "
				+ "AND persona_id = ? AND active = true", Integer.class, admin.organizationId(), persona.get("id")))
				.as("perfil de paciente vigente").isEqualTo(1);
		assertThat(resultado.get("enlace").asString()).isEqualTo("/pacientes/" + persona.get("id"));
	}

	@Test
	void la_edicion_cambia_el_mail_y_nada_mas() throws Exception {
		String apellido = "Editable" + sufijo();
		long personaId = paciente(admin, apellido, "Marta", "viejo@ejemplo.test", "3514440000");
		Map<String, Object> antes = jdbc.queryForMap("SELECT apellido, nombre, tipo_documento, numero_documento, "
				+ "telefono, fecha_nacimiento, active FROM persona WHERE id = ?", personaId);
		String nuevo = "nuevo-" + sufijo() + "@ejemplo.test";

		JsonNode r = decir(admin, "cambiale el mail a " + apellido + ", " + nuevo,
				Map.of("accion", "PACIENTE_EDICION", "persona", apellido, "email", nuevo));

		assertThat(r.get("resultado").asString()).as(r.toString()).isEqualTo("PROPUESTA");
		JsonNode accion = r.get("accion");
		assertThat(accion.get("tipo").asString()).isEqualTo("PACIENTE_EDICION");
		assertThat(accion.get("bloqueos")).as(accion.toString()).isEmpty();
		JsonNode tarjeta = accion.get("paciente");
		assertThat(tarjeta.get("personaId").asLong()).isEqualTo(personaId);
		assertThat(tarjeta.get("anterior").get("email").asString()).isEqualTo("viejo@ejemplo.test");
		assertThat(tarjeta.get("datos").get("email").asString()).isEqualTo(nuevo);
		assertThat(jdbc.queryForObject("SELECT email FROM persona WHERE id = ?", String.class, personaId))
				.as("proponer no graba").isEqualTo("viejo@ejemplo.test");

		JsonNode resultado = confirmar(admin, admin.consultorioId(), accion.get("id").asLong(), "paciente", tarjeta, 200);

		assertThat(resultado.get("estado").asString()).as(resultado.toString()).isEqualTo("CONFIRMADA");
		assertThat(jdbc.queryForObject("SELECT email FROM persona WHERE id = ?", String.class, personaId)).isEqualTo(nuevo);
		assertThat(jdbc.queryForMap("SELECT apellido, nombre, tipo_documento, numero_documento, telefono, "
				+ "fecha_nacimiento, active FROM persona WHERE id = ?", personaId)).isEqualTo(antes);
	}

	// ============================================================================ f. llegada

	@Test
	void quien_no_puede_registrar_la_sesion_obtiene_la_llegada() throws Exception {
		String apellido = "Llegador" + sufijo();
		long personaId = paciente(admin, apellido, "Pedro", null, null);
		long turnoId = turnoDeHoy(personaId, 5);

		Map<String, Object> entendido = new LinkedHashMap<>();
		entendido.put("accion", "ATENCION");
		entendido.put("persona", apellido);
		entendido.put("practicas", List.of(practica));
		JsonNode r = decir(administrativo, "hoy vino " + apellido + " e hizo " + practica, entendido);

		assertThat(r.get("resultado").asString()).as(r.toString()).isEqualTo("PROPUESTA");
		JsonNode accion = r.get("accion");
		assertThat(accion.get("tipo").asString()).isEqualTo("LLEGADA");
		assertThat(accion.get("llegada").get("turnoId").asLong()).isEqualTo(turnoId);
		assertThat(accion.get("avisos").toString()).contains("los registra el profesional");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recepcion WHERE turno_id = ?", Integer.class, turnoId))
				.as("proponer no graba").isZero();

		JsonNode resultado = confirmar(administrativo, admin.consultorioId(), accion.get("id").asLong(), "llegada",
				accion.get("llegada"), 200);

		assertThat(resultado.get("estado").asString()).as(resultado.toString()).isEqualTo("CONFIRMADA");
		Map<String, Object> recepcion = jdbc.queryForMap(
				"SELECT estado, llegada_por_cuenta_id FROM recepcion WHERE turno_id = ?", turnoId);
		assertThat(recepcion.get("estado")).isEqualTo("LLEGO");
		assertThat(((Number) recepcion.get("llegada_por_cuenta_id")).longValue()).isEqualTo(administrativo.cuentaId());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sesion WHERE turno_id = ?", Integer.class, turnoId))
				.as("la llegada no abre una sesion").isZero();
	}

	// ============================================================================ g. atencion

	@Test
	void el_profesional_registra_la_atencion_con_su_tratamiento_y_la_cierra() throws Exception {
		String apellido = "Atendido" + sufijo();
		long personaId = paciente(admin, apellido, "Lucas", null, null);
		long turnoId = turnoDeHoy(personaId, 45);
		String frase = "hoy vino " + apellido + " e hizo " + practica;

		Map<String, Object> entendido = new LinkedHashMap<>();
		entendido.put("accion", "ATENCION");
		entendido.put("persona", apellido);
		entendido.put("practicas", List.of(practica));
		JsonNode r = decir(profesional, frase, entendido);

		assertThat(r.get("resultado").asString()).as(r.toString()).isEqualTo("PROPUESTA");
		JsonNode accion = r.get("accion");
		assertThat(accion.get("tipo").asString()).isEqualTo("ATENCION");
		assertThat(accion.get("bloqueos")).as(accion.toString()).isEmpty();
		JsonNode atencion = accion.get("atencion");
		assertThat(atencion.get("turnoId").asLong()).isEqualTo(turnoId);
		assertThat(atencion.get("nota").asString()).isEqualTo(frase);
		assertThat(atencion.get("cerrar").asBoolean()).isTrue();
		assertThat(atencion.get("tratamientos")).hasSize(1);
		assertThat(atencion.get("tratamientos").get(0).get("practicaId").asLong()).isEqualTo(practicaId);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sesion WHERE turno_id = ?", Integer.class, turnoId))
				.as("proponer no graba").isZero();

		JsonNode resultado = confirmar(profesional, admin.consultorioId(), accion.get("id").asLong(), "atencion",
				atencion, 200);

		assertThat(resultado.get("estado").asString()).as(resultado.toString()).isEqualTo("CONFIRMADA");
		assertThat(resultado.get("texto").asString()).as(resultado.toString()).contains("la cerré");
		Map<String, Object> sesion = jdbc.queryForMap("SELECT id, estado, asistencia, nota_de_cierre, cerrada_en, "
				+ "profesional_membership_id FROM sesion WHERE turno_id = ?", turnoId);
		assertThat(sesion.get("estado")).isEqualTo("CERRADA");
		assertThat(sesion.get("asistencia")).isEqualTo("PRESENTE");
		assertThat(sesion.get("nota_de_cierre")).isEqualTo(frase);
		assertThat(sesion.get("cerrada_en")).isNotNull();
		assertThat(((Number) sesion.get("profesional_membership_id")).longValue()).isEqualTo(profesionalMembershipId);
		long sesionId = ((Number) sesion.get("id")).longValue();
		assertThat(resultado.get("enlace").asString()).isEqualTo("/atencion/sesiones/" + sesionId);
		assertThat(jdbc.queryForList("SELECT practica_id FROM tratamiento_realizado WHERE sesion_id = ?", Long.class,
				sesionId)).containsExactly(practicaId);
		Map<String, Object> deuda = jdbc.queryForMap("SELECT persona_id, importe_original, saldo, estado FROM obligacion "
				+ "WHERE sesion_id = ? AND responsable = 'PACIENTE'", sesionId);
		assertThat(((Number) deuda.get("persona_id")).longValue()).isEqualTo(personaId);
		assertThat((BigDecimal) deuda.get("importe_original")).isEqualByComparingTo("8500");
		assertThat(deuda.get("estado")).as("cerrar no cobra").isEqualTo("PENDIENTE");

		// La nota clinica y el nombre no quedan en la fila de la accion.
		String guardado = jdbc.queryForObject("SELECT referencias::text || ' ' || resultado::text FROM asistente_accion "
				+ "WHERE id = ?", String.class, accion.get("id").asLong()).toLowerCase(Locale.ROOT);
		assertThat(guardado).contains("sesionid")
				.doesNotContain(apellido.toLowerCase(Locale.ROOT)).doesNotContain("hizo");
	}

	// ============================================================================ correcciones de la revision

	@Test
	void un_pago_que_quedo_en_curso_se_vuelve_a_confirmar_sin_cobrar_dos_veces() throws Exception {
		String apellido = "Colgado" + sufijo();
		long personaId = paciente(admin, apellido, "Raul", null, null);
		deuda(personaId);
		JsonNode accion = decir(admin, apellido + " pagó 8500 en efectivo",
				Map.of("accion", "PAGO", "persona", apellido, "monto", "8500")).get("accion");
		long accionId = accion.get("id").asLong();
		assertThat(confirmar(admin, admin.consultorioId(), accionId, "pago", accion.get("pago"), 200)
				.get("estado").asString()).isEqualTo("CONFIRMADA");
		assertThat(cobrosDe(personaId)).isEqualTo(1);

		// El proceso murio despues de cobrar y antes de marcar la fila: quedo EN_CURSO, tomada hace rato.
		assertThat(jdbc.update("UPDATE asistente_accion SET estado = 'EN_CURSO', resultado = NULL, "
				+ "resuelta_en = NULL, tomada_en = CURRENT_TIMESTAMP - INTERVAL '3 minutes' WHERE id = ?", accionId))
				.isEqualTo(1);

		JsonNode otraVez = confirmar(admin, admin.consultorioId(), accionId, "pago", accion.get("pago"), 200);

		assertThat(otraVez.get("estado").asString()).as(otraVez.toString()).isEqualTo("CONFIRMADA");
		assertThat(cobrosDe(personaId)).as("la clave de idempotencia devuelve el mismo cobro").isEqualTo(1);
		assertThat(estadoDeLaAccion(accionId)).isEqualTo("CONFIRMADA");
	}

	@Test
	void un_pago_recien_tomado_por_otro_pedido_no_se_vuelve_a_tomar() throws Exception {
		String apellido = "Tomado" + sufijo();
		long personaId = paciente(admin, apellido, "Elsa", null, null);
		JsonNode accion = decir(admin, apellido + " pagó 4000 en efectivo",
				Map.of("accion", "PAGO", "persona", apellido, "monto", "4000")).get("accion");
		long accionId = accion.get("id").asLong();
		jdbc.update("UPDATE asistente_accion SET estado = 'EN_CURSO', tomada_en = CURRENT_TIMESTAMP WHERE id = ?",
				accionId);

		HttpResponse<String> r = post("/api/v1/consultorios/" + admin.consultorioId() + "/asistente/acciones/"
				+ accionId + "/confirmacion", admin.token(), JSON.writeValueAsString(Map.of("pago", accion.get("pago"))));

		assertThat(r.statusCode()).as(r.body()).isNotEqualTo(200);
		assertThat(cobrosDe(personaId)).isZero();
	}

	@Test
	void la_deuda_de_otra_sede_no_entra_en_el_cobro_y_se_avisa() throws Exception {
		String apellido = "Dosedes" + sufijo();
		long personaId = paciente(admin, apellido, "Nora", null, null);
		long deOtraSede = deuda(personaId);
		// La deuda pasa a ser de otra sede: basta el consultorio_id, que es lo que mira la regla.
		jdbc.update("UPDATE obligacion SET consultorio_id = ? WHERE id = ?", otroCentro.consultorioId(), deOtraSede);

		JsonNode accion = decir(admin, apellido + " pagó 17000 en efectivo",
				Map.of("accion", "PAGO", "persona", apellido, "monto", "17000")).get("accion");

		JsonNode pago = accion.get("pago");
		assertThat(pago.get("imputaciones")).as(accion.toString()).isEmpty();
		assertThat(pago.get("anticipo").decimalValue()).isEqualByComparingTo("17000");
		assertThat(accion.get("avisos").toString()).contains("otra sede");
	}

	@Test
	void cerrar_con_un_tratamiento_sin_reconocer_se_rechaza_sin_abrir_la_sesion() throws Exception {
		String apellido = "Sinpractica" + sufijo();
		long personaId = paciente(admin, apellido, "Ines", null, null);
		long turnoId = turnoDeHoy(personaId, 90);
		String desconocida = "tratamiento" + sufijo();

		Map<String, Object> entendido = new LinkedHashMap<>();
		entendido.put("accion", "ATENCION");
		entendido.put("persona", apellido);
		entendido.put("practicas", List.of(practica, desconocida));
		JsonNode accion = decir(profesional, "hoy vino " + apellido + " e hizo " + practica + " y " + desconocida,
				entendido).get("accion");

		JsonNode atencion = accion.get("atencion");
		assertThat(atencion.get("cerrar").asBoolean()).isTrue();
		assertThat(atencion.get("tratamientos")).as(accion.toString()).hasSize(2);
		JsonNode sinPractica = atencion.get("tratamientos").get(1).get("practicaId");
		assertThat(sinPractica == null || sinPractica.isNull()).isTrue();

		JsonNode resultado = confirmar(profesional, admin.consultorioId(), accion.get("id").asLong(), "atencion",
				atencion, 200);

		assertThat(resultado.get("estado").asString()).as(resultado.toString()).isEqualTo("RECHAZADA");
		assertThat(resultado.get("texto").asString()).contains("sin reconocer");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sesion WHERE turno_id = ?", Integer.class, turnoId))
				.as("no se abrio nada").isZero();
	}

	// ============================================================================ h. aislamiento

	@Test
	void la_propuesta_de_una_cuenta_no_la_confirma_otra_ni_en_otra_sede() throws Exception {
		String apellido = "Ajeno" + sufijo();
		Map<String, Object> entendido = new LinkedHashMap<>();
		entendido.put("accion", "PACIENTE_ALTA");
		entendido.put("nombre", "Nora");
		entendido.put("apellido", apellido);
		JsonNode accion = decir(admin, "agregá a Nora " + apellido, entendido).get("accion");
		assertThat(accion).isNotNull();
		long accionId = accion.get("id").asLong();
		JsonNode tarjeta = accion.get("paciente");

		// Otra cuenta del mismo centro, en la misma sede y con el permiso: no existe para ella.
		JsonNode deOtraCuenta = confirmar(administrativo, admin.consultorioId(), accionId, "paciente", tarjeta, 404);
		assertThat(deOtraCuenta.get("type").asString()).endsWith("/not-found");
		// Otro centro, en su propia sede.
		confirmar(otroCentro, otroCentro.consultorioId(), accionId, "paciente", tarjeta, 404);
		// Otro centro apuntando a la sede ajena.
		confirmar(otroCentro, admin.consultorioId(), accionId, "paciente", tarjeta, 404);
		HttpResponse<String> descarteAjeno = post("/api/v1/consultorios/" + admin.consultorioId()
				+ "/asistente/acciones/" + accionId + "/descarte", administrativo.token(), "");
		assertThat(descarteAjeno.statusCode()).as(descarteAjeno.body()).isEqualTo(404);

		assertThat(personasCon(apellido)).isZero();
		assertThat(estadoDeLaAccion(accionId)).isEqualTo("PROPUESTA");

		// Quien la pidio la descarta, y despues ya no se puede confirmar.
		HttpResponse<String> descarte = post("/api/v1/consultorios/" + admin.consultorioId()
				+ "/asistente/acciones/" + accionId + "/descarte", admin.token(), "");
		assertThat(descarte.statusCode()).as(descarte.body()).isEqualTo(204);
		assertThat(estadoDeLaAccion(accionId)).isEqualTo("DESCARTADA");
		JsonNode tarde = confirmar(admin, admin.consultorioId(), accionId, "paciente", tarjeta, 409);
		assertThat(tarde.get("type").asString()).endsWith("/accion-no-vigente");
		assertThat(personasCon(apellido)).isZero();
	}

	@Test
	void una_pregunta_sigue_siendo_una_consulta_aunque_el_modelo_devuelva_una_accion() throws Exception {
		// EsPregunta corta antes del interpretador de acciones: la respuesta nunca es una tarjeta.
		modelo.responderJson(JSON.writeValueAsString(Map.of("accion", "PACIENTE_ALTA", "nombre", "Nora",
				"apellido", "Consulta")));

		HttpResponse<String> r = post("/api/v1/consultorios/" + admin.consultorioId() + "/asistente/consultas",
				admin.token(), JSON.writeValueAsString(Map.of("pregunta", "¿quién es Nora Consulta?")));

		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		JsonNode cuerpo = JSON.readTree(r.body());
		assertThat(cuerpo.get("resultado").asString()).isNotEqualTo("PROPUESTA");
		assertThat(cuerpo.get("accion") == null || cuerpo.get("accion").isNull()).isTrue();
	}

	// ============================================================================ soporte

	/** Dice la frase con el modelo de prueba contestando {@code entendido}. */
	private JsonNode decir(Sesion sesion, String frase, Map<String, Object> entendido) throws Exception {
		modelo.responderJson(JSON.writeValueAsString(entendido));
		HttpResponse<String> r = post("/api/v1/consultorios/" + sesion.consultorioId() + "/asistente/consultas",
				sesion.token(), JSON.writeValueAsString(Map.of("pregunta", frase)));
		assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
		return JSON.readTree(r.body());
	}

	/** Confirma devolviendo la tarjeta tal cual vino en la propuesta. */
	private JsonNode confirmar(Sesion sesion, long consultorioId, long accionId, String bloque, JsonNode tarjeta,
			int esperado) throws Exception {
		HttpResponse<String> r = post("/api/v1/consultorios/" + consultorioId + "/asistente/acciones/" + accionId
				+ "/confirmacion", sesion.token(), JSON.writeValueAsString(Map.of(bloque, tarjeta)));
		assertThat(r.statusCode()).as(r.body()).isEqualTo(esperado);
		return JSON.readTree(r.body());
	}

	private String estadoDeLaAccion(long accionId) {
		return jdbc.queryForObject("SELECT estado FROM asistente_accion WHERE id = ?", String.class, accionId);
	}

	private int cobrosDe(long personaId) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM cobro WHERE persona_id = ?", Integer.class, personaId);
	}

	private int personasCon(String apellido) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM persona WHERE apellido = ?", Integer.class, apellido);
	}

	private BigDecimal saldoDeCaja() {
		return jdbc.queryForObject("SELECT saldo_arqueo FROM jornada_caja WHERE organization_id = ? "
				+ "AND consultorio_id = ? AND estado = 'ABIERTA'", BigDecimal.class, admin.organizationId(),
				admin.consultorioId());
	}

	private ZoneId zonaDeLaSede() {
		return ZoneId.of(jdbc.queryForObject("SELECT timezone FROM consultorio WHERE id = ?", String.class,
				admin.consultorioId()));
	}

	/** Una persona con perfil de paciente vigente, por los servicios de {@code person}. */
	private long paciente(Sesion centro, String apellido, String nombre, String email, String telefono) {
		OperatingActor actor = new OperatingActor(centro.cuentaId(), false, centro.organizationId(), centro.consultorioId());
		long id = personas.crear(actor, new PersonaAltaCommand(TipoDocumento.DNI,
				String.valueOf(20_000_000 + ThreadLocalRandom.current().nextInt(9_000_000)), apellido, nombre, null,
				email, telefono, null, true)).id();
		perfiles.activar(actor, id, "Alta sintetica");
		return id;
	}

	/** Una deuda del paciente de 8500 en el centro A, colgada de una sesion sintetica. */
	private long deuda(long personaId) {
		long historiaId = InsertarConId.insertar(jdbc, """
				INSERT INTO historia_clinica (organization_id, persona_id, abierta_en, abierta_por,
				                              active, version, created_at, updated_at)
				VALUES (?, ?, CURRENT_TIMESTAMP, ?, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", admin.organizationId(), personaId, admin.cuentaId());
		long sesionId = InsertarConId.insertar(jdbc, """
				INSERT INTO sesion (organization_id, consultorio_id, historia_clinica_id, oferta_id,
				                    profesional_membership_id, estado, iniciada_en,
				                    iniciada_por_cuenta_id, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, 'BORRADOR', CURRENT_TIMESTAMP, ?, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", admin.organizationId(), admin.consultorioId(), historiaId, ofertaId, profesionalMembershipId,
				profesional.cuentaId());
		return InsertarConId.insertar(jdbc, """
				INSERT INTO obligacion (organization_id, consultorio_id, sesion_id, persona_id,
				                        responsable, importe_original, saldo, moneda, estado,
				                        oferta_id, snapshot_nombre, snapshot_precio, devengada_en,
				                        version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'PACIENTE', 8500.00, 8500.00, 'ARS', 'PENDIENTE',
				        ?, 'Sesion sintetica', 8500.00, CURRENT_TIMESTAMP, 0,
				        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", admin.organizationId(), admin.consultorioId(), sesionId, personaId, ofertaId);
	}

	/**
	 * Un turno de hoy con el profesional, que empezo hace {@code haceMinutos}. Las horas salen del
	 * motor; cada caso usa un tramo distinto para que la grilla sea posible.
	 */
	private long turnoDeHoy(long personaId, int haceMinutos) {
		return InsertarConId.insertar(jdbc, """
				INSERT INTO turno (organization_id, consultorio_id, oferta_id, persona_id,
				                   profesional_membership_id, inicio, fin, estado,
				                   reservado_por_cuenta_id, reservado_en, version,
				                   created_at, updated_at)
				VALUES (?, ?, ?, ?, ?, (CURRENT_TIMESTAMP - make_interval(mins => ?)),
				        (CURRENT_TIMESTAMP - make_interval(mins => ?) + INTERVAL '30 minutes'), 'RESERVADO', ?,
				        CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", admin.organizationId(), admin.consultorioId(), ofertaId, personaId, profesionalMembershipId,
				haceMinutos, haceMinutos, admin.cuentaId());
	}

	/**
	 * Una clase de hoy, sin iniciar, con esas personas inscriptas por el servicio real. Nace dentro de
	 * dos dias para poder inscribir (una clase que ya empezo no admite inscripciones) y despues se
	 * trae a ahora.
	 */
	private long claseDeHoyCon(String titulo, List<Long> personaIds) {
		String s = sufijo();
		long servicioId = InsertarConId.insertar(jdbc, """
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'GRUPAL', false, false, true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", "CLA-" + s.toUpperCase(Locale.ROOT), "Servicio grupal " + s);
		long ofertaGrupalId = InsertarConId.insertar(jdbc, """
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				        genera_registro_clinico, requiere_profesional, requiere_espacio,
				        vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'GRUPAL', 60, 10, false, false, false, false, false,
				        CAST(CURRENT_DATE - INTERVAL '5 years' AS date), true, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
				""", admin.organizationId(), admin.consultorioId(), servicioId, "Grupal " + s);
		long claseId = InsertarConId.insertar(jdbc, """
				INSERT INTO clase_programada (organization_id, consultorio_id, oferta_id, titulo,
				                              inicio, fin, capacidad, estado,
				                              programado_por_cuenta_id, programado_en, version)
				VALUES (?, ?, ?, ?, (CURRENT_TIMESTAMP + INTERVAL '2 days'),
				        (CURRENT_TIMESTAMP + INTERVAL '2 days' + INTERVAL '1 hours'),
				        10, 'PROGRAMADA', ?, CURRENT_TIMESTAMP, 0)
				""", admin.organizationId(), admin.consultorioId(), ofertaGrupalId, titulo, admin.cuentaId());
		com.akine.activity.application.OperatingActor actor = new com.akine.activity.application.OperatingActor(
				admin.cuentaId(), false, admin.organizationId(), admin.consultorioId());
		for (long personaId : personaIds) {
			inscripciones.inscribir(actor, admin.consultorioId(), claseId, new InscribirCommand(personaId, false, null));
		}
		jdbc.update("UPDATE clase_programada SET inicio = CURRENT_TIMESTAMP - INTERVAL '5 minutes', "
				+ "fin = CURRENT_TIMESTAMP + INTERVAL '55 minutes' WHERE id = ?", claseId);
		return claseId;
	}

	private static String sufijo() {
		// Solo letras: la busqueda del padron y el control de lo extraido pliegan el texto.
		StringBuilder s = new StringBuilder();
		for (int i = 0; i < 7; i++) {
			s.append((char) ('a' + ThreadLocalRandom.current().nextInt(26)));
		}
		return s.toString();
	}

	private Sesion conRolEn(Sesion centro, String etiqueta, String rol) throws Exception {
		Sesion cuenta = altaCompleta(etiqueta);
		membershipService.createDirect(centro.cuentaId(), false, centro.organizationId(),
				new DirectMembershipCommand(cuenta.cuentaId(), centro.consultorioId(), rol, "prueba sintetica"));
		String token = seleccionarContexto(login(cuenta.email()), centro.organizationId(), centro.consultorioId());
		return new Sesion(cuenta.cuentaId(), cuenta.email(), centro.organizationId(), centro.consultorioId(), token);
	}

	record Sesion(long cuentaId, String email, long organizationId, long consultorioId, String token) {
	}

	private Sesion altaCompleta(String etiqueta) throws Exception {
		String email = etiqueta + "-" + UUID.randomUUID() + "@ejemplo.test";
		String alta = "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\","
				+ "\"firstName\":\"Sintetica\",\"lastName\":\"DePrueba\","
				+ "\"organizationName\":\"Centro " + etiqueta + "\"}";
		HttpResponse<String> registro = post("/api/v1/auth/register", null, alta,
				"Idempotency-Key", UUID.randomUUID().toString());
		assertThat(registro.statusCode()).as(registro.body()).isEqualTo(202);
		assertThat(jdbc.update("UPDATE cuenta SET estado = 'ACTIVA' WHERE email_normalizado = ?",
				email.toLowerCase(Locale.ROOT))).isEqualTo(1);

		String preContexto = login(email);
		HttpResponse<String> contextos = http.send(HttpRequest.newBuilder(uri("/api/v1/me/contexts"))
				.header("Authorization", "Bearer " + preContexto).GET().build(), HttpResponse.BodyHandlers.ofString());
		JsonNode primero = JSON.readTree(contextos.body()).get(0);
		long organizationId = primero.get("organizationId").asLong();
		long consultorioId = primero.get("consultorioId").asLong();
		Long cuentaId = jdbc.queryForObject("SELECT id FROM cuenta WHERE email_normalizado = ?", Long.class,
				email.toLowerCase(Locale.ROOT));
		return new Sesion(cuentaId, email, organizationId, consultorioId,
				seleccionarContexto(preContexto, organizationId, consultorioId));
	}

	private String login(String email) throws Exception {
		HttpResponse<String> login = post("/api/v1/auth/login", null,
				"{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
		assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
		return JSON.readTree(login.body()).get("accessToken").asString();
	}

	private String seleccionarContexto(String preContexto, long organizationId, long consultorioId) throws Exception {
		HttpResponse<String> contexto = post("/api/v1/auth/context", preContexto,
				"{\"organizationId\":" + organizationId + ",\"consultorioId\":" + consultorioId + "}");
		assertThat(contexto.statusCode()).as(contexto.body()).isEqualTo(200);
		return JSON.readTree(contexto.body()).get("accessToken").asString();
	}

	private HttpResponse<String> post(String ruta, String token, String cuerpo, String... headers) throws Exception {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri(ruta))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(cuerpo));
		if (token != null) {
			builder.header("Authorization", "Bearer " + token);
		}
		if (headers.length > 0) {
			builder.headers(headers);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String ruta) {
		return URI.create("http://localhost:" + puerto + ruta);
	}
}
