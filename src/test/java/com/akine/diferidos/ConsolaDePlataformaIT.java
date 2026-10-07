package com.akine.diferidos;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AKINE-A-7 de punta a punta, contra el stack real: el endpoint "¿tengo rol de plataforma?" y la
 * resolucion de solicitudes de catalogo que publica el concepto global (RF-M06-005).
 *
 * <p>Por el camino real —HTTP, login Argon2id, contexto canjeado, MySQL— y afirmando contra la
 * BASE: que la solicitud quedo resuelta no se prueba con el 200, se prueba con la fila.
 *
 * <p>Los codigos llevan sufijo aleatorio: el catalogo GLOBAL es compartido por todos los tests
 * del contexto y un codigo fijo haria depender el resultado del orden de ejecucion.
 */
class ConsolaDePlataformaIT extends BaseEscenarioDiferido {

	private static final String ROL = "/api/v1/me/platform-role";
	private static final String SOLICITUDES = "/api/v1/catalogo-solicitudes";

	// =================================================================================
	// 1. GET /me/platform-role
	// =================================================================================

	@Test
	@DisplayName("El rol de plataforma se consulta sin contexto: true para el admin, false para un "
			+ "tenant con o sin contexto, 403 sin sesion; y se revalida en cada request")
	void el_rol_de_plataforma_se_consulta_sin_contexto_y_se_revalida() {
		Sesion centro = altaCompleta("a7-rol-tenant");
		Sesion admin = altaCompleta("a7-rol-admin");
		sembrarRolDePlataforma(admin.cuentaId());

		// Una cuenta de tenant con el token pre_context: sin la excepcion del filtro esto seria
		// 403 missing-tenant-context, y la pantalla no sabria que mostrar.
		assertThat(rol(centro.tokenPreContexto())).isFalse();
		assertThat(rol(centro.token())).isFalse();

		assertThat(rol(admin.tokenPreContexto())).isTrue();
		assertThat(rol(admin.token())).isTrue();

		// Sin token responde la cadena de seguridad, antes del controller, igual que en toda
		// ruta autenticada: 401 unauthorized. La regla "403, nunca 401" es para quien SI tiene
		// sesion, que es el caso en el que un 401 borraria un token valido.
		Respuesta anonima = get(ROL, null);
		assertThat(anonima.status()).as("sin sesion: %s", anonima.body())
				.isEqualTo(get("/api/v1/me/contexts", null).status())
				.isEqualTo(401);
		assertProblemaLimpio(anonima);

		// No sale del token: vencido el rol, el MISMO token responde false en el request
		// siguiente.
		jdbc.update("""
				UPDATE platform_role
				   SET valid_until = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 SECOND)
				 WHERE account_id = ?
				""", admin.cuentaId());
		assertThat(rol(admin.tokenPreContexto())).isFalse();
	}

	// =================================================================================
	// 2. Resolver solicitudes
	// =================================================================================

	@Test
	@DisplayName("Aprobar publica el concepto global con los datos de la plataforma, lo vincula a "
			+ "la solicitud, lo audita en el tenant y el centro lo ve; un tenant no resuelve")
	void aprobar_publica_el_concepto_global() {
		Sesion plataforma = administradorDePlataforma();
		Sesion centro = altaCompleta("a7-aprueba");
		String sufijo = sufijo();
		String propuesto = "Terapia ocupacional " + sufijo;
		String codigo = "TO-" + sufijo;
		String nombrePublicado = "Terapia Ocupacional " + sufijo;

		long solicitudId = solicitar(centro, "ESPECIALIDAD", propuesto, null);
		assertThat(idsDeSolicitudes(plataforma)).contains(solicitudId);

		// Un tenant no resuelve, ni siquiera la suya.
		Respuesta intrusa = resolver(centro, solicitudId,
				"{\"estado\":\"APROBADA\",\"nota\":\"Me la apruebo\",\"version\":0,"
						+ "\"codigo\":\"" + codigo + "\"}");
		assertThat(intrusa.status()).as("tenant resolviendo: %s", intrusa.body()).isEqualTo(403);
		assertThat(estadoDe(solicitudId)).isEqualTo("PENDIENTE");
		assertThat(filasConCodigo(codigo)).isZero();

		Respuesta aprobada = resolver(plataforma, solicitudId,
				"{\"estado\":\"APROBADA\",\"nota\":\"Se normaliza el nombre\",\"version\":0,"
						+ "\"codigo\":\"" + codigo + "\",\"nombre\":\"" + nombrePublicado + "\"}");
		assertThat(aprobada.status()).as("aprobacion: %s", aprobada.body()).isEqualTo(200);
		assertThat(aprobada.texto("estado")).isEqualTo("APROBADA");
		long conceptoId = aprobada.json().get("conceptoId").asLong();

		// La base: un concepto GLOBAL (owner_key 0, sin tenant) con lo que dispuso la
		// plataforma, y la solicitud apuntandolo.
		assertThat(jdbc.queryForMap("""
				SELECT codigo, name, owner_key, organization_id, active
				  FROM especialidad WHERE id = ?
				""", conceptoId))
				.containsEntry("codigo", codigo)
				.containsEntry("name", nombrePublicado)
				.containsEntry("owner_key", 0L)
				.containsEntry("organization_id", null);
		assertThat(jdbc.queryForMap("""
				SELECT estado, concepto_id, resuelta_por_account_id, resolucion_nota
				  FROM catalogo_solicitud WHERE id = ?
				""", solicitudId))
				.containsEntry("estado", "APROBADA")
				.containsEntry("concepto_id", conceptoId)
				.containsEntry("resuelta_por_account_id", plataforma.cuentaId())
				.containsEntry("resolucion_nota", "Se normaliza el nombre");

		// Auditoria: la resolucion bajo el tenant que pidio, con el concepto; el alta del
		// concepto, sin tenant.
		assertThat(jdbc.queryForMap("""
				SELECT organization_id, actor_account_id, previous_state, new_state,
				       JSON_UNQUOTE(JSON_EXTRACT(details, '$.conceptoId')) AS concepto
				  FROM audit_event
				 WHERE event_type = 'CATALOGO_SOLICITUD_RESOLVED' AND entity_id = ?
				""", solicitudId))
				.containsEntry("organization_id", centro.organizationId())
				.containsEntry("actor_account_id", plataforma.cuentaId())
				.containsEntry("previous_state", "PENDIENTE")
				.containsEntry("new_state", "APROBADA")
				.containsEntry("concepto", String.valueOf(conceptoId));
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE event_type = 'CATALOGO_CREATED' AND entity_id = ?
				   AND organization_id IS NULL AND actor_account_id = ?
				""", Long.class, conceptoId, plataforma.cuentaId()))
				.isEqualTo(1L);

		// Y el centro que lo pidio lo ve en su catalogo, como cualquier global.
		Respuesta listado = get("/api/v1/catalogos/especialidades?q=" + codigo, centro.token());
		assertThat(listado.status()).isEqualTo(200);
		assertThat(listado.json().get("content").get(0).get("id").asLong()).isEqualTo(conceptoId);
	}

	@Test
	@DisplayName("Rechazar exige motivo, no publica nada, y la nota queda guardada")
	void rechazar_con_motivo_no_publica() {
		Sesion plataforma = administradorDePlataforma();
		Sesion centro = altaCompleta("a7-rechaza");
		String nombre = "Practica dudosa " + sufijo();
		long solicitudId = solicitar(centro, "ESPECIALIDAD", nombre, "PD-" + sufijo());

		Respuesta sinMotivo = resolver(plataforma, solicitudId,
				"{\"estado\":\"RECHAZADA\",\"nota\":\" \",\"version\":0}");
		assertThat(sinMotivo.status()).as("sin motivo: %s", sinMotivo.body()).isEqualTo(400);

		Respuesta rechazo = resolver(plataforma, solicitudId,
				"{\"estado\":\"RECHAZADA\",\"nota\":\"Ya existe como Kinesiologia\",\"version\":0}");
		assertThat(rechazo.status()).as("rechazo: %s", rechazo.body()).isEqualTo(200);

		assertThat(jdbc.queryForMap(
				"SELECT estado, concepto_id, resolucion_nota FROM catalogo_solicitud WHERE id = ?",
				solicitudId))
				.containsEntry("estado", "RECHAZADA")
				.containsEntry("concepto_id", null)
				.containsEntry("resolucion_nota", "Ya existe como Kinesiologia");
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM especialidad WHERE name = ?", Long.class, nombre))
				.isZero();
	}

	@Test
	@DisplayName("Si el codigo ya esta tomado en el catalogo global, 409 y la solicitud sigue "
			+ "PENDIENTE, sin concepto y sin auditoria de resolucion")
	void un_codigo_tomado_deja_la_solicitud_pendiente() {
		Sesion plataforma = administradorDePlataforma();
		Sesion centro = altaCompleta("a7-choque");
		String codigo = "CH-" + sufijo();

		Respuesta existente = post("/api/v1/catalogos/especialidades", plataforma.token(),
				"{\"alcance\":\"GLOBAL\",\"codigo\":\"" + codigo + "\",\"name\":\"Ya publicada "
						+ codigo + "\"}");
		assertThat(existente.status()).as("alta global previa: %s", existente.body())
				.isEqualTo(201);

		long solicitudId = solicitar(centro, "ESPECIALIDAD", "Otra cosa " + codigo, codigo);
		Respuesta choque = resolver(plataforma, solicitudId,
				"{\"estado\":\"APROBADA\",\"nota\":\"Ok\",\"version\":0}");

		assertThat(choque.status()).as("choque: %s", choque.body()).isEqualTo(409);
		assertThat(choque.texto("type"))
				.isEqualTo("https://akine.app/problems/catalogo-code-taken");
		assertProblemaLimpio(choque);

		assertThat(jdbc.queryForMap(
				"SELECT estado, concepto_id, version FROM catalogo_solicitud WHERE id = ?",
				solicitudId))
				.containsEntry("estado", "PENDIENTE")
				.containsEntry("concepto_id", null)
				.containsEntry("version", 0L);
		assertThat(filasConCodigo(codigo)).isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE event_type = 'CATALOGO_SOLICITUD_RESOLVED' AND entity_id = ?
				""", Long.class, solicitudId)).isZero();
	}

	@Test
	@DisplayName("Dos administradores aprueban la misma solicitud a la vez con codigos distintos: "
			+ "queda un concepto y una resolucion")
	void dos_aprobaciones_simultaneas_dejan_un_solo_concepto() {
		Sesion plataforma = administradorDePlataforma();
		Sesion otraPlataforma = administradorDePlataforma();
		Sesion centro = altaCompleta("a7-carrera");
		String sufijo = sufijo();
		String codigoA = "CA-" + sufijo;
		String codigoB = "CB-" + sufijo;
		long solicitudId = solicitar(centro, "ESPECIALIDAD", "Carrera " + sufijo, null);

		List<Concurrencia.Resultado<Integer>> resultados = Concurrencia.enParalelo(List.of(
				aprobar(plataforma, solicitudId, codigoA, "Carrera A " + sufijo),
				aprobar(otraPlataforma, solicitudId, codigoB, "Carrera B " + sufijo)));

		List<Integer> codigos = resultados.stream().map(Concurrencia.Resultado::valor).toList();
		assertThat(codigos).as("una gana y la otra se entera: %s", codigos)
				.containsExactlyInAnyOrder(200, 409);

		Long conceptoId = jdbc.queryForObject(
				"SELECT concepto_id FROM catalogo_solicitud WHERE id = ?", Long.class, solicitudId);
		assertThat(conceptoId).isNotNull();
		assertThat(jdbc.queryForObject(
				"SELECT COUNT(*) FROM especialidad WHERE codigo IN (?, ?)",
				Long.class, codigoA, codigoB))
				.as("el concepto del perdedor se deshizo con su transaccion")
				.isEqualTo(1L);
		assertThat(jdbc.queryForObject("""
				SELECT COUNT(*) FROM audit_event
				 WHERE event_type = 'CATALOGO_SOLICITUD_RESOLVED' AND entity_id = ?
				""", Long.class, solicitudId)).isEqualTo(1L);
	}

	// =================================================================================
	// Fixtures y utilidades
	// =================================================================================

	private Sesion administradorDePlataforma() {
		Sesion sesion = altaCompleta("a7-plataforma");
		sembrarRolDePlataforma(sesion.cuentaId());
		return sesion;
	}

	private static String sufijo() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

	private boolean rol(String token) {
		Respuesta respuesta = get(ROL, token);
		assertThat(respuesta.status()).as("rol de plataforma: %s", respuesta.body()).isEqualTo(200);
		return respuesta.json().get("platformAdmin").asBoolean();
	}

	private long solicitar(Sesion sesion, String tipo, String nombre, String codigo) {
		Respuesta pedido = post(SOLICITUDES, sesion.token(),
				"{\"tipo\":\"" + tipo + "\",\"nombrePropuesto\":\"" + nombre + "\","
						+ (codigo == null ? "" : "\"codigoPropuesto\":\"" + codigo + "\",")
						+ "\"justificacion\":\"Tres profesionales del centro la ejercen\"}");
		assertThat(pedido.status()).as("alta de solicitud: %s", pedido.body()).isEqualTo(201);
		return pedido.json().get("id").asLong();
	}

	private Respuesta resolver(Sesion sesion, long solicitudId, String cuerpo) {
		return post(SOLICITUDES + "/" + solicitudId + "/resolve", sesion.token(), cuerpo);
	}

	private Callable<Integer> aprobar(Sesion sesion, long solicitudId, String codigo, String nombre) {
		return () -> resolver(sesion, solicitudId,
				"{\"estado\":\"APROBADA\",\"nota\":\"Carrera\",\"version\":0,\"codigo\":\""
						+ codigo + "\",\"nombre\":\"" + nombre + "\"}").status();
	}

	private List<Long> idsDeSolicitudes(Sesion sesion) {
		Respuesta listado = get(SOLICITUDES + "?estado=PENDIENTE", sesion.token());
		assertThat(listado.status()).as("bandeja: %s", listado.body()).isEqualTo(200);
		List<Long> ids = new java.util.ArrayList<>();
		for (int i = 0; i < listado.json().size(); i++) {
			ids.add(listado.json().get(i).get("id").asLong());
		}
		return ids;
	}

	private String estadoDe(long solicitudId) {
		return jdbc.queryForObject(
				"SELECT estado FROM catalogo_solicitud WHERE id = ?", String.class, solicitudId);
	}

	private long filasConCodigo(String codigo) {
		Long total = jdbc.queryForObject(
				"SELECT COUNT(*) FROM especialidad WHERE codigo = ?", Long.class, codigo);
		return total == null ? 0L : total;
	}
}
