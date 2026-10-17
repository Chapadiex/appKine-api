package com.akine.diferidos;

import java.net.http.HttpRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.OperatingActor;
import com.akine.organization.application.MembershipService;
import com.akine.organization.spi.DirectMembershipCommand;
import com.akine.person.support.CoberturaFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AKINE-09.02: el aislamiento de tenant y de sede de toda la superficie de F9 ({@code activity}),
 * endpoint por endpoint, por HTTP real y contra MySQL.
 *
 * <h2>Que prueba</h2>
 *
 * <p>La organizacion A tiene una clase, una inscripcion, un producto y un pase vendido, todos en
 * su sede A1. Cuatro atacantes, cada uno con el permiso que la operacion pide —para que la
 * respuesta la decida el ALCANCE y no la matriz—:
 *
 * <ul>
 *   <li>el {@code ORG_ADMIN} y un {@code PROFESIONAL} de <b>otra organizacion</b> (B);</li>
 *   <li>un {@code CONSULTORIO_ADMIN} y un {@code PROFESIONAL} de <b>otra sede de la misma
 *       organizacion</b> (A2), cuyo alcance es su sede.</li>
 * </ul>
 *
 * <p>Cada uno pide los recursos de A1 de las dos formas posibles: <b>por su propia sede</b> (el
 * recurso no existe en su alcance) y <b>por la sede de A1</b> (la sede no es suya).
 *
 * <ul>
 *   <li>Otra organizacion: <b>404 en las dos formas, nunca 403</b>; un 403 confirma que el id
 *       existe.</li>
 *   <li>Otra sede de la misma organizacion, por su propia sede: <b>404</b>. Es la forma que
 *       destapo el defecto de 09.02: confirmar una inscripcion de A1 pidiendola por A2 daba 200 y
 *       la confirmaba.</li>
 *   <li>Otra sede de la misma organizacion, por la sede de A1: <b>403</b> {@code forbidden}, y es
 *       la regla del evaluador ({@code PermissionEvaluatorService}: la sede es de su organizacion,
 *       existe para el). Lo que se exige es que sea el MISMO 403 sin mirar el recurso, o sea que
 *       no delate ningun id. Pasarlo a 404 es la decision U-46 (a).</li>
 * </ul>
 *
 * <p>La agenda unificada de la sede (escenario 41 de {@code docs/tests-diferidos.md}) entra en la
 * misma regla: la de A no se lee desde afuera, y la propia de cada atacante no trae la clase de A.
 *
 * <p>Y al final nada de A se movio.
 */
class ActividadAislamientoIT extends BaseEscenarioDiferido {

	private static final String MOTIVO = "prueba sintetica de 09.02";
	private static final String JUSTIFICACION = "Hardening 09.02, dato sintetico";

	@Autowired
	private MembershipService membershipService;

	@Autowired
	private InscripcionService inscripcionService;

	@Test
	@DisplayName("09.02 · ningun endpoint de activity alcanza recursos de otra organizacion ni de otra sede: 404, nunca 403")
	void la_superficie_de_f9_esta_aislada() {
		// --- La organizacion A, con todo lo que F9 crea, por HTTP ---
		Sesion a = altaCompleta("f9-aisl-a");
		long sedeA = a.consultorioId();
		long oferta = ofertaGrupal(a.organizationId(), sedeA);
		CoberturaFixtures datos = new CoberturaFixtures(jdbc);
		long persona = datos.persona(a.organizationId(), "Sintetica", "Aislamiento", "38111222");
		datos.perfilPaciente(a.organizationId(), persona);

		String cA = "/api/v1/consultorios/" + sedeA;
		Instant inicio = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
		Respuesta clase = post(cA + "/clases/ofertas/" + oferta, a.token(), """
				{"inicio":"%s","fin":"%s","capacidad":4,"titulo":"Clase de aislamiento"}
				""".formatted(inicio, inicio.plus(1, ChronoUnit.HOURS)));
		assertThat(clase.status()).as("A programa su clase: %s", clase.body()).isEqualTo(201);
		long claseId = ultimoId("clase_programada", a.organizationId());
		LocalDate fechaDeLaClase = inicio.atZone(java.time.ZoneId.of(jdbc.queryForObject(
				"SELECT timezone FROM consultorio WHERE id = ?", String.class, sedeA))).toLocalDate();
		Respuesta agendaDeA = get(cA + "/agenda?fecha=" + fechaDeLaClase, a.token());
		assertThat(agendaDeA.body()).as("control positivo: el duenio ve su clase en la agenda unificada")
				.contains("Clase de aislamiento");

		Respuesta inscripcion = post(cA + "/clases/" + claseId + "/inscripciones", a.token(),
				"{\"personaId\":" + persona + "}");
		assertThat(inscripcion.status()).as("A inscribe: %s", inscripcion.body()).isEqualTo(201);
		long inscripcionId = ultimoId("inscripcion_clase", a.organizationId());

		Respuesta producto = post(cA + "/productos", a.token(), """
				{"ofertaId":%d,"nombre":"Pack aislamiento","creditos":4,"precio":20000.00,
				 "moneda":"ARS","vigenciaDias":30,"vigenciaDesde":"%s"}
				""".formatted(oferta, LocalDate.now().minusDays(1)));
		assertThat(producto.status()).as("A crea su producto: %s", producto.body()).isEqualTo(201);
		long productoId = ultimoId("producto_servicio", a.organizationId());

		Respuesta venta = post(cA + "/pases", a.token(),
				"{\"productoId\":" + productoId + ",\"personaId\":" + persona + "}");
		assertThat(venta.status()).as("A vende un pase: %s", venta.body()).isEqualTo(201);
		long paseId = ultimoId("pase_servicio", a.organizationId());

		Map<String, Object> claseAntes = fila("clase_programada", claseId);
		Map<String, Object> inscripcionAntes = fila("inscripcion_clase", inscripcionId);
		Map<String, Object> productoAntes = fila("producto_servicio", productoId);
		Map<String, Object> paseAntes = fila("pase_servicio", paseId);

		// --- Los atacantes ---
		Sesion b = altaCompleta("f9-aisl-b");
		String profB = miembro("f9-aisl-prof-b", b.organizationId(), b.consultorioId(), b.cuentaId(),
				"PROFESIONAL");
		long sedeA2 = otraSede(a.organizationId());
		String adminA2 = miembro("f9-aisl-ca-a2", a.organizationId(), sedeA2, a.cuentaId(),
				"CONSULTORIO_ADMIN");
		String profA2 = miembro("f9-aisl-prof-a2", a.organizationId(), sedeA2, a.cuentaId(), "PROFESIONAL");

		List<Atacante> atacantes = List.of(
				new Atacante("ORG_ADMIN de B", b.token(), b.consultorioId(), true, false),
				new Atacante("PROFESIONAL de B", profB, b.consultorioId(), false, false),
				new Atacante("CONSULTORIO_ADMIN de A2", adminA2, sedeA2, true, true),
				new Atacante("PROFESIONAL de A2", profA2, sedeA2, false, true));

		SoftAssertions celdas = new SoftAssertions();
		for (Atacante atacante : atacantes) {
			for (long sede : List.of(atacante.sedePropia(), sedeA)) {
				String c = "/api/v1/consultorios/" + sede;
				String k = c + "/clases/" + claseId;
				String i = k + "/inscripciones/" + inscripcionId;
				List<Pedido> pedidos = new ArrayList<>();

				// Clases e inscripciones: los dos roles las tienen por base.
				pedidos.add(Pedido.get(k));
				pedidos.add(Pedido.get(k + "/historial"));
				pedidos.add(Pedido.get(k + "/inscripciones"));
				pedidos.add(Pedido.get(k + "/cupos"));
				pedidos.add(Pedido.get(k + "/detalle-operativo"));
				pedidos.add(Pedido.post(k + "/reprogramacion", """
						{"inicio":"%s","fin":"%s","capacidad":6,"version":0}
						""".formatted(inicio.plus(1, ChronoUnit.DAYS), inicio.plus(25, ChronoUnit.HOURS))));
				pedidos.add(Pedido.post(k + "/cancelacion", "{\"motivo\":\"intrusion\"}"));
				pedidos.add(Pedido.post(k + "/inscripciones", "{\"personaId\":" + persona + "}"));
				pedidos.add(Pedido.post(i + "/confirmacion", "{}"));
				pedidos.add(Pedido.post(i + "/cancelacion", "{\"motivo\":\"intrusion\"}"));
				// Asistencia.
				pedidos.add(Pedido.post(k + "/inicio", "{}"));
				pedidos.add(Pedido.post(k + "/cierre", "{}"));
				pedidos.add(Pedido.post(k + "/asistencias",
						"{\"inscripcionId\":" + inscripcionId + ",\"resultado\":\"PRESENTE\"}"));
				pedidos.add(Pedido.post(k + "/asistencias/lote",
						"{\"items\":[{\"inscripcionId\":" + inscripcionId + ",\"resultado\":\"PRESENTE\"}]}"));
				pedidos.add(Pedido.post(k + "/ingresos",
						"{\"personaId\":" + persona + ",\"resultado\":\"PRESENTE\"}"));
				if (sede == sedeA) {
					// La agenda unificada de la sede de A (escenario 41): por la propia sede es
					// una lectura legitima y se mira abajo.
					pedidos.add(Pedido.get(c + "/agenda?fecha=" + fechaDeLaClase));
				}
				if (!atacante.administrativo()) {
					// Lo clinico de la clase: el profesional tiene hc:read/hc:write por base.
					pedidos.add(Pedido.clinico("GET", i + "/elegibilidad-clinica", null));
					pedidos.add(Pedido.clinico("GET", i + "/derivaciones", null));
					pedidos.add(Pedido.clinico("POST", i + "/derivaciones", "{\"casoClinicoId\":1}"));
					pedidos.add(Pedido.clinico("POST", i + "/derivaciones/1/reversion",
							"{\"motivo\":\"intrusion\"}"));
					pedidos.add(Pedido.clinico("POST", i + "/atencion-clinica", "{}"));
					pedidos.add(Pedido.clinico("GET", k + "/atencion-clinica", null));
					pedidos.add(Pedido.clinico("POST", k + "/atencion-clinica/lote",
							"{\"items\":[{\"inscripcionId\":" + inscripcionId + "}]}"));
				} else {
					// Productos y pases: los tienen los administrativos y el ORG_ADMIN.
					pedidos.add(Pedido.get(c + "/productos/" + productoId));
					pedidos.add(Pedido.put(c + "/productos/" + productoId, "{\"nombre\":\"Pisado\",\"version\":0}"));
					pedidos.add(Pedido.post(c + "/productos/" + productoId + "/baja",
							"{\"motivo\":\"intrusion\"}"));
					pedidos.add(Pedido.post(c + "/pases",
							"{\"productoId\":" + productoId + ",\"personaId\":" + persona + "}"));
					pedidos.add(Pedido.get(c + "/pases/" + paseId));
					pedidos.add(Pedido.get(c + "/pases/" + paseId + "/movimientos"));
					pedidos.add(Pedido.put(c + "/pases/" + paseId + "/renovacion",
							"{\"activa\":true,\"version\":0}"));
					pedidos.add(Pedido.get(c + "/personas/" + persona + "/pases"));
					pedidos.add(Pedido.post(c + "/pases/" + paseId + "/ajustes",
							"{\"cantidad\":-1,\"motivo\":\"intrusion\",\"version\":0}"));
					pedidos.add(Pedido.post(c + "/pases/" + paseId + "/anulacion",
							"{\"motivo\":\"intrusion\",\"version\":0}"));
				}

				boolean sedeHermana = atacante.mismaOrganizacion() && sede == sedeA;
				for (Pedido pedido : pedidos) {
					Respuesta respuesta = enviar(pedido, atacante.token());
					int esperado = sedeHermana ? 403 : 404;
					if (atacante.mismaOrganizacion() && !sedeHermana
							&& pedido.ruta().endsWith("/personas/" + persona + "/pases")) {
						// La persona es de la ORGANIZACION (regla 8): existe para A2. Sus pases de
						// A1 no: la lista de A2 sale vacia.
						celdas.assertThat(respuesta.status()).as("[%s] pases de la persona por A2",
								atacante.nombre()).isEqualTo(200);
						celdas.assertThat(respuesta.body()).as("[%s] sin los pases de A1",
								atacante.nombre()).isEqualTo("[]");
						continue;
					}
					celdas.assertThat(respuesta.status())
							.as("[%s] %s %s -> %s", atacante.nombre(), pedido.metodo(), pedido.ruta(),
									respuesta.body())
							.isEqualTo(esperado);
					if (sedeHermana) {
						celdas.assertThat(respuesta.body())
								.as("[%s] %s: el 403 no mira el recurso", atacante.nombre(), pedido.ruta())
								.contains("/problems/forbidden")
								.doesNotContain("Aislamiento");
					}
					celdas.assertThat(respuesta.body())
							.as("[%s] %s: sin internals", atacante.nombre(), pedido.ruta())
							.doesNotContain("com.akine")
							.doesNotContain("org.springframework");
				}
			}

			// Los listados de su propia sede no traen nada de A.
			String propia = "/api/v1/consultorios/" + atacante.sedePropia();
			Respuesta grilla = get(propia + "/clases?desde=" + inicio.minus(1, ChronoUnit.DAYS)
					+ "&hasta=" + inicio.plus(1, ChronoUnit.DAYS), atacante.token());
			celdas.assertThat(grilla.status()).as("[%s] grilla propia: %s", atacante.nombre(), grilla.body())
					.isEqualTo(200);
			celdas.assertThat(grilla.body()).as("[%s] la grilla propia no trae la clase de A", atacante.nombre())
					.doesNotContain("Clase de aislamiento");
			Respuesta agenda = get(propia + "/agenda?fecha=" + fechaDeLaClase, atacante.token());
			celdas.assertThat(agenda.status()).as("[%s] agenda unificada propia: %s", atacante.nombre(),
					agenda.body()).isEqualTo(200);
			celdas.assertThat(agenda.body()).as("[%s] la agenda propia no trae la clase de A", atacante.nombre())
					.doesNotContain("Clase de aislamiento")
					.doesNotContain("\"eventoId\":" + claseId + ",");
			if (atacante.administrativo()) {
				Respuesta catalogo = get(propia + "/productos", atacante.token());
				celdas.assertThat(catalogo.status()).as("[%s] catalogo propio", atacante.nombre()).isEqualTo(200);
				celdas.assertThat(catalogo.body()).as("[%s] el catalogo propio no trae el producto de A",
						atacante.nombre()).doesNotContain("Pack aislamiento");
			}
		}
		celdas.assertAll();

		// Y nada de A se movio.
		assertThat(fila("clase_programada", claseId)).isEqualTo(claseAntes);
		assertThat(fila("inscripcion_clase", inscripcionId)).isEqualTo(inscripcionAntes);
		assertThat(fila("producto_servicio", productoId)).isEqualTo(productoAntes);
		assertThat(fila("pase_servicio", paseId)).isEqualTo(paseAntes);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM inscripcion_clase WHERE clase_id = ?",
				Long.class, claseId)).as("ningun atacante inscribio en la clase de A").isEqualTo(1L);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pase_servicio WHERE producto_id = ?",
				Long.class, productoId)).as("ningun atacante vendio el producto de A").isEqualTo(1L);
	}

	@Test
	@DisplayName("09.02 · el PLATFORM_ADMIN con soporte lee los participantes de una clase y deja SUPPORT_ACCESS_USED")
	void la_lectura_de_participantes_por_soporte_queda_auditada() {
		Sesion a = altaCompleta("f9-soporte");
		long oferta = ofertaGrupal(a.organizationId(), a.consultorioId());
		CoberturaFixtures datos = new CoberturaFixtures(jdbc);
		long persona = datos.persona(a.organizationId(), "Sintetica", "Soporte", "38111333");
		String cA = "/api/v1/consultorios/" + a.consultorioId();
		Instant inicio = Instant.now().plus(4, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
		assertThat(post(cA + "/clases/ofertas/" + oferta, a.token(), """
				{"inicio":"%s","fin":"%s","capacidad":4}
				""".formatted(inicio, inicio.plus(1, ChronoUnit.HOURS))).status()).isEqualTo(201);
		long claseId = ultimoId("clase_programada", a.organizationId());
		assertThat(post(cA + "/clases/" + claseId + "/inscripciones", a.token(),
				"{\"personaId\":" + persona + "}").status()).isEqualTo(201);

		long soporte = altaCompleta("f9-soporte-admin").cuentaId();
		sembrarRolDePlataforma(soporte);
		jdbc.update("""
				INSERT INTO support_access (organization_id, account_id, reason,
				                            granted_by_account_id, valid_from, valid_until,
				                            active, version, created_at, updated_at)
				VALUES (?, ?, 'Incidente sintetico de test de integracion', ?,
				        DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE),
				        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 1 HOUR), 1, 0,
				        UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", a.organizationId(), soporte, soporte);

		assertThat(inscripcionService.participantes(
				new OperatingActor(soporte, true, a.organizationId(), null), a.consultorioId(), claseId))
				.as("con soporte vigente la lectura se concede")
				.hasSize(1);

		// La fila TIENE que estar en la base: la lectura es readOnly y la auditoria va aparte.
		List<Map<String, Object>> usos = jdbc.queryForList("""
				SELECT consultorio_id, details FROM audit_event
				 WHERE event_type = 'SUPPORT_ACCESS_USED' AND actor_account_id = ?
				   AND organization_id = ?
				""", soporte, a.organizationId());
		assertThat(usos).as("la lectura de participantes bajo soporte deja SUPPORT_ACCESS_USED")
				.isNotEmpty()
				.allSatisfy(fila -> assertThat(String.valueOf(fila.get("details")))
						.containsAnyOf("inscripcion:read", "clase:read"));
		assertThat(usos).extracting(fila -> String.valueOf(fila.get("details")))
				.anySatisfy(detalle -> assertThat(detalle).contains("inscripcion:read"));
	}

	// =================================================================================

	private record Atacante(String nombre, String token, long sedePropia, boolean administrativo,
			boolean mismaOrganizacion) {
	}

	private record Pedido(String metodo, String ruta, String cuerpo, boolean justificar) {

		static Pedido get(String ruta) {
			return new Pedido("GET", ruta, null, false);
		}

		static Pedido post(String ruta, String cuerpo) {
			return new Pedido("POST", ruta, cuerpo, false);
		}

		static Pedido put(String ruta, String cuerpo) {
			return new Pedido("PUT", ruta, cuerpo, false);
		}

		static Pedido clinico(String metodo, String ruta, String cuerpo) {
			return new Pedido(metodo, ruta, cuerpo, true);
		}
	}

	private Respuesta enviar(Pedido pedido, String token) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri(pedido.ruta()))
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", "application/json")
				.method(pedido.metodo(), pedido.cuerpo() == null
						? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofString(pedido.cuerpo()));
		if (pedido.justificar()) {
			builder.header("X-Justificacion-Acceso", JUSTIFICACION);
		}
		return ejecutar(builder.build());
	}

	/** Una oferta grupal y clinica de la sede, vendible por pack. */
	private long ofertaGrupal(long org, long sede) {
		String sufijo = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		jdbc.update("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'GRUPAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "AISL-" + sufijo, "Servicio " + sufijo);
		long servicio = jdbc.queryForObject("SELECT id FROM servicio WHERE codigo = ?", Long.class,
				"AISL-" + sufijo);
		jdbc.update("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				        genera_registro_clinico, requiere_profesional, requiere_espacio,
				        esquema_cobro, momento_devengo,
				        vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'GRUPAL', 60, 10, 0, 0, 0, 0, 0, 'POR_PACK', 'VENTA',
				        DATE_SUB(CURDATE(), INTERVAL 1 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, servicio, "Oferta " + sufijo);
		return jdbc.queryForObject(
				"SELECT id FROM oferta_servicio_consultorio WHERE organization_id = ? AND nombre_comercial = ?",
				Long.class, org, "Oferta " + sufijo);
	}

	private long otraSede(long org) {
		String nombre = "Sede A2 " + UUID.randomUUID().toString().substring(0, 8);
		jdbc.update("""
				INSERT INTO consultorio (organization_id, name, timezone, active, version, created_at, updated_at)
				VALUES (?, ?, 'America/Argentina/Cordoba', 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, nombre);
		return jdbc.queryForObject("SELECT id FROM consultorio WHERE organization_id = ? AND name = ?",
				Long.class, org, nombre);
	}

	/** Una cuenta real con membership en la sede pedida, logueada y con el contexto elegido. */
	private String miembro(String etiqueta, long org, long sede, long fundador, String rol) {
		Sesion cuenta = altaCompleta(etiqueta);
		membershipService.createDirect(fundador, false, org,
				new DirectMembershipCommand(cuenta.cuentaId(), sede, rol, MOTIVO));
		return seleccionarContexto(login(cuenta.email()), org, sede);
	}

	private long ultimoId(String tabla, long org) {
		return jdbc.queryForObject("SELECT MAX(id) FROM " + tabla + " WHERE organization_id = ?",
				Long.class, org);
	}

	private Map<String, Object> fila(String tabla, long id) {
		return jdbc.queryForMap("SELECT * FROM " + tabla + " WHERE id = ?", id);
	}
}
