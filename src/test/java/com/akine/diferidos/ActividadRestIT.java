package com.akine.diferidos;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.akine.organization.application.PermissionEvaluatorService;
import com.akine.organization.domain.exception.PermissionDeniedException;
import com.akine.organization.spi.PermissionQuery;
import com.akine.person.support.CoberturaFixtures;

import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * Escenarios 42, 48 y 59 de {@code docs/tests-diferidos.md}: <b>la capa REST de clases,
 * inscripciones y asistencia</b>, por HTTP real y contra MySQL.
 *
 * <p>El aislamiento de tenant de esas mismas operaciones ya lo cubre {@link ActividadAislamientoIT}
 * endpoint por endpoint, y la matriz por rol {@link MatrizDePermisosIT}; esta clase agrega lo que
 * ninguna de las dos mira:
 *
 * <ul>
 *   <li>el circuito feliz entero por HTTP —codigos, cuerpos y lo que queda en la base—, desde
 *       programar hasta cerrar;</li>
 *   <li>que el lote no sea un enumerador: un item con la inscripcion de otro tenant contesta
 *       {@code not-found} adentro de un 200, y los demas items se aplican;</li>
 *   <li><b>las tres decisiones de permiso de F9</b>: listar inscriptos y el detalle operativo
 *       exigen {@code inscripcion:read} y no alcanza con {@code clase:read}; marcar exige
 *       {@code asistencia:manage}. Hoy todo rol que tiene uno tiene el otro, asi que con roles
 *       reales la decision no se ve: el evaluador de permisos real se espia y se le niega UN codigo,
 *       y todo lo demas sigue siendo la cadena real.</li>
 * </ul>
 */
class ActividadRestIT extends BaseEscenarioDiferido {

	@MockitoSpyBean
	private PermissionEvaluatorService permisos;

	@AfterEach
	void restaurarPermisos() {
		reset(permisos);
	}

	@Test
	@DisplayName("42/48/59 · Programar, reprogramar, inscribir, confirmar, iniciar, marcar, lote, ingreso, detalle y cierre por HTTP")
	void circuito_completo_por_http() {
		Sesion a = altaCompleta("f9-rest-a");
		long sede = a.consultorioId();
		String c = "/api/v1/consultorios/" + sede;
		long oferta = ofertaGrupal(a.organizationId(), sede);
		CoberturaFixtures datos = new CoberturaFixtures(jdbc);
		long p1 = datos.persona(a.organizationId(), "Sintetica", "Rest Uno", "38222111");
		long p2 = datos.persona(a.organizationId(), "Sintetica", "Rest Dos", "38222112");
		long p3 = datos.persona(a.organizationId(), "Sintetica", "Rest Tres", "38222113");
		Instant inicio = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);

		// --- Clases (42) ---
		Respuesta programada = post(c + "/clases/ofertas/" + oferta, a.token(), """
				{"inicio":"%s","fin":"%s","capacidad":4,"titulo":"Clase REST"}
				""".formatted(inicio, inicio.plus(1, ChronoUnit.HOURS)));
		assertThat(programada.status()).as(programada.body()).isEqualTo(201);
		long clase = programada.json().get("id").asLong();
		String k = c + "/clases/" + clase;

		Respuesta vista = get(k, a.token());
		assertThat(vista.status()).isEqualTo(200);
		assertThat(vista.texto("estado")).isEqualTo("PROGRAMADA");

		Respuesta movida = post(k + "/reprogramacion", a.token(), """
				{"inicio":"%s","fin":"%s","capacidad":3,"version":%d}
				""".formatted(inicio.plus(1, ChronoUnit.HOURS), inicio.plus(2, ChronoUnit.HOURS),
				vista.json().get("version").asLong()));
		assertThat(movida.status()).as(movida.body()).isEqualTo(200);
		assertThat(movida.json().get("capacidad").asInt()).isEqualTo(3);

		Respuesta lista = get(c + "/clases?desde=" + inicio.minus(1, ChronoUnit.DAYS) + "&hasta="
				+ inicio.plus(1, ChronoUnit.DAYS), a.token());
		assertThat(lista.status()).isEqualTo(200);
		assertThat(lista.body()).contains("Clase REST");

		Respuesta historial = get(k + "/historial", a.token());
		assertThat(historial.status()).isEqualTo(200);
		assertThat(historial.body()).contains("CREACION").contains("REPROGRAMACION");

		// --- Inscripciones (48) ---
		long i1 = inscribir(a, k, p1);
		long i2 = inscribir(a, k, p2);
		Respuesta confirmada = post(k + "/inscripciones/" + i1 + "/confirmacion", a.token(), "{}");
		assertThat(confirmada.status()).as(confirmada.body()).isEqualTo(200);
		assertThat(confirmada.texto("estado")).isEqualTo("CONFIRMADA");

		Respuesta inscriptos = get(k + "/inscripciones", a.token());
		assertThat(inscriptos.status()).isEqualTo(200);
		assertThat(inscriptos.json().size()).isEqualTo(2);
		Respuesta cupos = get(k + "/cupos", a.token());
		assertThat(cupos.status()).isEqualTo(200);
		assertThat(cupos.json().get("ocupados").asInt()).isEqualTo(2);

		// --- Asistencia (59) ---
		Respuesta iniciada = post(k + "/inicio", a.token(), "{}");
		assertThat(iniciada.status()).as(iniciada.body()).isEqualTo(200);
		assertThat(iniciada.texto("estado")).isEqualTo("EN_CURSO");

		Respuesta marcada = post(k + "/asistencias", a.token(),
				"{\"inscripcionId\":" + i1 + ",\"resultado\":\"PRESENTE\"}");
		assertThat(marcada.status()).as(marcada.body()).isEqualTo(201);
		long asistencia = marcada.json().get("asistencia").get("id").asLong();

		long ajena = inscripcionDeOtroTenant();
		Respuesta lote = post(k + "/asistencias/lote", a.token(), """
				{"items":[{"inscripcionId":%d,"resultado":"AUSENTE"},{"inscripcionId":%d,"resultado":"PRESENTE"}]}
				""".formatted(i2, ajena));
		assertThat(lote.status()).as(lote.body()).isEqualTo(200);
		assertThat(lote.json().get("exitosos").asInt()).isEqualTo(1);
		JsonNode itemAjeno = lote.json().get("items").get(1);
		assertThat(itemAjeno.get("problemType").asString())
				.as("la inscripcion de otro tenant es not-found, nunca 'no es tuya'")
				.endsWith("not-found");
		assertThat(lote.body()).doesNotContain("Ajena");

		Respuesta ingreso = post(k + "/ingresos", a.token(),
				"{\"personaId\":" + p3 + ",\"resultado\":\"PRESENTE_TARDE\"}");
		assertThat(ingreso.status()).as(ingreso.body()).isEqualTo(201);

		Respuesta detalle = get(k + "/detalle-operativo", a.token());
		assertThat(detalle.status()).as(detalle.body()).isEqualTo(200);
		assertThat(detalle.json().get("presentes").asInt()).isEqualTo(2);
		assertThat(detalle.json().get("ausentes").asInt()).isEqualTo(1);
		assertThat(detalle.json().get("ocupados").asInt()).isEqualTo(3);

		Respuesta eventos = get(k + "/asistencias/" + asistencia + "/historial", a.token());
		assertThat(eventos.status()).isEqualTo(200);
		assertThat(eventos.json().size()).isEqualTo(1);

		Respuesta cierre = post(k + "/cierre", a.token(), "{}");
		assertThat(cierre.status()).as(cierre.body()).isEqualTo(200);
		assertThat(cierre.json().get("clase").get("estado").asString()).isEqualTo("REALIZADA");

		// Lo que quedo en la base, no lo que dijo la respuesta.
		assertThat(jdbc.queryForList("SELECT origen FROM asistencia_actividad WHERE clase_id = ? ORDER BY id",
				String.class, clase)).containsExactly("MOSTRADOR", "LOTE", "INGRESO_SIN_INSCRIPCION");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asistencia_actividad WHERE inscripcion_id = ?",
				Integer.class, ajena)).isZero();

		// --- Cancelar (42) ---
		Respuesta otra = post(c + "/clases/ofertas/" + oferta, a.token(), """
				{"inicio":"%s","fin":"%s","capacidad":4}
				""".formatted(inicio.plus(1, ChronoUnit.DAYS), inicio.plus(25, ChronoUnit.HOURS)));
		long otraClase = otra.json().get("id").asLong();
		Respuesta cancelada = post(c + "/clases/" + otraClase + "/cancelacion", a.token(),
				"{\"motivo\":\"Sin instructor\"}");
		assertThat(cancelada.status()).as(cancelada.body()).isEqualTo(200);
		assertThat(cancelada.texto("estado")).isEqualTo("CANCELADA");
	}

	@Test
	@DisplayName("48/59 · Sin inscripcion:read, la clase se ve pero ni los inscriptos ni el detalle operativo; sin asistencia:manage no se marca")
	void las_decisiones_de_permiso_de_f9() {
		Sesion a = altaCompleta("f9-rest-permisos");
		String c = "/api/v1/consultorios/" + a.consultorioId();
		long oferta = ofertaGrupal(a.organizationId(), a.consultorioId());
		long persona = new CoberturaFixtures(jdbc).persona(a.organizationId(), "Sintetica", "Permiso", "38222114");
		Instant inicio = Instant.now().plus(6, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
		long clase = post(c + "/clases/ofertas/" + oferta, a.token(), """
				{"inicio":"%s","fin":"%s","capacidad":4}
				""".formatted(inicio, inicio.plus(1, ChronoUnit.HOURS))).json().get("id").asLong();
		String k = c + "/clases/" + clase;
		long inscripcion = inscribir(a, k, persona);
		assertThat(post(k + "/inicio", a.token(), "{}").status()).isEqualTo(200);

		negar("inscripcion:read");
		assertThat(get(k, a.token()).status()).as("clase:read alcanza para ver la clase").isEqualTo(200);
		Respuesta inscriptos = get(k + "/inscripciones", a.token());
		assertThat(inscriptos.status()).as(inscriptos.body()).isEqualTo(403);
		assertThat(inscriptos.body()).contains("/problems/forbidden")
				.contains("\"requiredPermission\":\"inscripcion:read\"")
				.as("sin un dato del participante").doesNotContain("38222114");
		assertThat(get(k + "/detalle-operativo", a.token()).status()).isEqualTo(403);

		reset(permisos);
		negar("asistencia:manage");
		assertThat(get(k + "/inscripciones", a.token()).status()).isEqualTo(200);
		Respuesta marcar = post(k + "/asistencias", a.token(),
				"{\"inscripcionId\":" + inscripcion + ",\"resultado\":\"PRESENTE\"}");
		assertThat(marcar.status()).as(marcar.body()).isEqualTo(403);
		assertThat(marcar.body()).contains("\"requiredPermission\":\"asistencia:manage\"");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asistencia_actividad WHERE clase_id = ?",
				Integer.class, clase)).isZero();
	}

	// =================================================================================

	/** El evaluador real, salvo para el codigo pedido: ese se niega como lo negaria la matriz. */
	private void negar(String codigo) {
		doAnswer(llamada -> {
			PermissionQuery consulta = llamada.getArgument(0);
			if (codigo.equals(consulta.permissionCode())) {
				throw new PermissionDeniedException(codigo, consulta.accountId(), consulta.organizationId());
			}
			return llamada.callRealMethod();
		}).when(permisos).requirePermission(any());
	}

	private long inscribir(Sesion sesion, String clase, long persona) {
		Respuesta inscripta = post(clase + "/inscripciones", sesion.token(), "{\"personaId\":" + persona + "}");
		assertThat(inscripta.status()).as(inscripta.body()).isEqualTo(201);
		return inscripta.json().get("inscripcion").get("id").asLong();
	}

	/** Una inscripcion viva de otra organizacion, por HTTP. */
	private long inscripcionDeOtroTenant() {
		Sesion b = altaCompleta("f9-rest-b");
		String cB = "/api/v1/consultorios/" + b.consultorioId();
		long oferta = ofertaGrupal(b.organizationId(), b.consultorioId());
		long persona = new CoberturaFixtures(jdbc).persona(b.organizationId(), "Sintetica", "Ajena", "38222115");
		Instant inicio = Instant.now().plus(8, ChronoUnit.DAYS).truncatedTo(ChronoUnit.HOURS);
		long clase = post(cB + "/clases/ofertas/" + oferta, b.token(), """
				{"inicio":"%s","fin":"%s","capacidad":4}
				""".formatted(inicio, inicio.plus(1, ChronoUnit.HOURS))).json().get("id").asLong();
		return inscribir(b, cB + "/clases/" + clase, persona);
	}

	/** Una oferta grupal de la sede, sin profesional ni box exigidos. */
	private long ofertaGrupal(long org, long sede) {
		String sufijo = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
		jdbc.update("""
				INSERT INTO servicio (codigo, nombre, naturaleza, modalidad_default,
				                      requiere_caso_clinico_default, genera_registro_clinico_default,
				                      active, version, created_at, updated_at)
				VALUES (?, ?, 'CLINICO', 'GRUPAL', 0, 0, 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", "REST-" + sufijo, "Servicio " + sufijo);
		long servicio = jdbc.queryForObject("SELECT id FROM servicio WHERE codigo = ?", Long.class,
				"REST-" + sufijo);
		jdbc.update("""
				INSERT INTO oferta_servicio_consultorio
				       (organization_id, consultorio_id, servicio_id, nombre_comercial, modalidad,
				        duracion_minutos, capacidad, admite_obra_social, requiere_caso_clinico,
				        genera_registro_clinico, requiere_profesional, requiere_espacio,
				        vigencia_desde, active, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 'GRUPAL', 60, 10, 0, 0, 0, 0, 0,
				        DATE_SUB(CURDATE(), INTERVAL 1 YEAR), 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
				""", org, sede, servicio, "Oferta " + sufijo);
		return jdbc.queryForObject(
				"SELECT id FROM oferta_servicio_consultorio WHERE organization_id = ? AND nombre_comercial = ?",
				Long.class, org, "Oferta " + sufijo);
	}
}
