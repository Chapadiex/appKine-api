package com.akine.diferidos;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-8 — CA-M03-002: la sede, su primer box y su horario general en un solo acto, contra MySQL.
 *
 * <p>Lo que ningun unitario puede probar es que las tres escrituras —{@code consultorio} y
 * {@code consultorio_alta} de {@code organization}, {@code espacio} y {@code consultorio_horario}
 * de {@code resource}— comparten UNA transaccion: la que abre el gate de plan. Si alguna corriera
 * en una transaccion propia, el rollback del segundo caso dejaria filas sueltas y este test las
 * encontraria contando en la base, no leyendo la respuesta.
 */
class AltaDeSedeEnUnActoIT extends BaseEscenarioDiferido {

	private static final String HORARIO_VALIDO = """
			[{"diaSemana":1,"horaDesde":"09:00","horaHasta":"13:00"},
			 {"diaSemana":1,"horaDesde":"14:00","horaHasta":"24:00"},
			 {"diaSemana":6,"horaDesde":"09:00","horaHasta":"12:00"}]""";

	@Test
	@DisplayName("CA-M03-002-01: la sede nace con su primer box y su horario general, y el "
			+ "reintento con la misma clave no duplica nada")
	void el_alta_completa_crea_sede_box_y_horario() {
		Sesion sesion = altaCompleta("a8-completa");
		habilitarMasDeUnaSede(sesion);
		String clave = UUID.randomUUID().toString();
		String cuerpo = cuerpo("Sede Completa", "{\"name\":\"Box 1\",\"capacidad\":2}", HORARIO_VALIDO);

		Respuesta alta = crearSede(sesion, cuerpo, clave);
		assertThat(alta.status()).as("alta en un acto: %s", alta.body()).isEqualTo(201);
		long sedeId = alta.json().get("id").asLong();

		assertThat(jdbc.queryForMap(
				"SELECT name, tipo, capacidad, organization_id FROM espacio WHERE consultorio_id = ?",
				sedeId))
				.containsEntry("name", "Box 1")
				.containsEntry("tipo", "BOX")
				.containsEntry("capacidad", 2)
				.containsEntry("organization_id", sesion.organizationId());
		assertThat(jdbc.queryForList(
				"SELECT CONCAT(dia_semana, ' ', TIME_FORMAT(hora_desde, '%H:%i'), '-', "
						+ "TIME_FORMAT(hora_hasta, '%H:%i')) FROM consultorio_horario "
						+ "WHERE consultorio_id = ? AND organization_id = ? AND active = 1 "
						+ "ORDER BY dia_semana, hora_desde",
				String.class, sedeId, sesion.organizationId()))
				.as("la medianoche se guarda como 24:00, no como 00:00 del dia siguiente")
				.containsExactly("1 09:00-13:00", "1 14:00-24:00", "6 09:00-12:00");
		assertThat(contar("SELECT COUNT(*) FROM audit_event WHERE consultorio_id = ? "
				+ "AND event_type IN ('CONSULTORIO_CREATED', 'ESPACIO_CREATED', "
				+ "'HORARIO_GENERAL_UPDATED')", sedeId))
				.as("las tres escrituras quedan auditadas")
				.isEqualTo(3);

		// El horario se lee por el calendario de la sede, que es donde vive.
		Respuesta calendario = get("/api/v1/consultorios/" + sedeId
				+ "/calendario?desde=2026-01-01&hasta=2026-02-01", sesion.token());
		assertThat(calendario.status()).as("calendario: %s", calendario.body()).isEqualTo(200);
		assertThat(calendario.json().get("horarioGeneral")).hasSize(3);
		assertThat(calendario.json().get("horarioGeneral").get(1).get("horaHasta").asString())
				.isEqualTo("24:00");

		// Reintento por timeout de red: misma clave, mismo cuerpo -> la misma sede, sin otro box.
		Respuesta reintento = crearSede(sesion, cuerpo, clave);
		assertThat(reintento.status()).isEqualTo(201);
		assertThat(reintento.json().get("id").asLong()).isEqualTo(sedeId);
		assertThat(contar("SELECT COUNT(*) FROM espacio WHERE consultorio_id = ?", sedeId))
				.isEqualTo(1);
		assertThat(contar("SELECT COUNT(*) FROM consultorio_horario WHERE consultorio_id = ?", sedeId))
				.isEqualTo(3);
	}

	@Test
	@DisplayName("CA-M03-002-04: un horario que se solapa rechaza el alta con 400 y no deja nada: "
			+ "ni sede, ni box, ni horario, ni la clave de idempotencia")
	void el_horario_invalido_revierte_el_alta_entera() {
		Sesion sesion = altaCompleta("a8-rollback");
		habilitarMasDeUnaSede(sesion);
		long organizationId = sesion.organizationId();
		long sedesAntes = contar(
				"SELECT COUNT(*) FROM consultorio WHERE organization_id = ?", organizationId);
		String clave = UUID.randomUUID().toString();

		// Pasa la validacion de formato: el solapamiento recien se detecta en resource, DESPUES
		// de insertar la sede. Es el rollback que importa probar.
		Respuesta alta = crearSede(sesion, cuerpo("Sede Fallida", "{\"name\":\"Box 1\"}", """
				[{"diaSemana":2,"horaDesde":"09:00","horaHasta":"13:00"},
				 {"diaSemana":2,"horaDesde":"12:00","horaHasta":"18:00"}]"""), clave);

		assertThat(alta.status()).as("alta con horario solapado: %s", alta.body()).isEqualTo(400);
		assertProblemaLimpio(alta);
		assertThat(contar("SELECT COUNT(*) FROM consultorio WHERE organization_id = ?", organizationId))
				.as("la sede no quedo").isEqualTo(sedesAntes);
		assertThat(contar("SELECT COUNT(*) FROM consultorio WHERE organization_id = ? AND name = ?",
				organizationId, "Sede Fallida")).isZero();
		assertThat(contar("SELECT COUNT(*) FROM consultorio_alta WHERE organization_id = ? "
				+ "AND idempotency_key = ?", organizationId, clave))
				.as("la clave no quedo registrada: el cliente puede corregir y reintentar con ella")
				.isZero();
		assertThat(contar("SELECT COUNT(*) FROM espacio e JOIN consultorio c ON c.id = e.consultorio_id "
				+ "WHERE c.organization_id = ? AND e.name = 'Box 1'", organizationId)).isZero();
		assertThat(contar("SELECT COUNT(*) FROM consultorio_horario WHERE organization_id = ?",
				organizationId)).isZero();

		// Y corregido, con la MISMA clave, entra.
		Respuesta corregida = crearSede(sesion,
				cuerpo("Sede Fallida", "{\"name\":\"Box 1\"}", HORARIO_VALIDO), clave);
		assertThat(corregida.status()).as("alta corregida: %s", corregida.body()).isEqualTo(201);
	}

	// =================================================================================
	// Auxiliares
	// =================================================================================

	private static String cuerpo(String nombre, String primerBox, String horario) {
		return "{\"name\":\"" + nombre + "\",\"timezone\":\"America/Argentina/Cordoba\","
				+ "\"primerBox\":" + primerBox + ",\"horarioGeneral\":" + horario + "}";
	}

	private Respuesta crearSede(Sesion sesion, String cuerpo, String clave) {
		return post("/api/v1/organizations/" + sesion.organizationId() + "/consultorios",
				sesion.token(), cuerpo, Map.of("Idempotency-Key", clave));
	}

	private long contar(String sql, Object... args) {
		Long total = jdbc.queryForObject(sql, Long.class, args);
		return total == null ? 0L : total;
	}

	/**
	 * El plan BASICO del alta self-service permite una sola sede: se pasa a PROFESIONAL por el
	 * endpoint real, con una cuenta de plataforma aparte. Mismo atajo y mismo motivo que
	 * {@code ConsultoriosIT}.
	 */
	private void habilitarMasDeUnaSede(Sesion sesion) {
		Sesion plataforma = altaCompleta("a8-plataforma");
		sembrarRolDePlataforma(plataforma.cuentaId());

		Respuesta cambio = post(
				"/api/v1/organizations/" + sesion.organizationId() + "/subscription/plan-changes",
				plataforma.token(),
				"{\"planCode\":\"PROFESIONAL\",\"expectedVersion\":0}");
		assertThat(cambio.status())
				.as("el tenant necesita un plan sin tope de sedes: %s", cambio.body())
				.isEqualTo(200);
	}
}
