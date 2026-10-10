package com.akine.diferidos;

import java.net.http.HttpRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akine.organization.application.MembershipService;
import com.akine.organization.spi.DirectMembershipCommand;
import com.akine.person.support.CoberturaFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AKINE-G-5: la matriz de permisos ({@code docs/seguridad/matriz-permisos-minima.md}) hecha
 * ejecutable, por HTTP y contra MySQL real.
 *
 * <h2>Que prueba y que no</h2>
 *
 * <p>{@code RolePermissionsTest} ya compara la tabla de asignacion base contra la matriz. Lo que
 * no prueba es que <b>cada endpoint pregunte por el permiso que la matriz le asigna a su
 * accion</b>: un controller que autoriza por pertenencia, o por el permiso de la familia vecina,
 * deja la tabla impecable y la matriz incumplida. Eso es lo que este test mira: para cada rol de
 * membership (ORG_ADMIN, CONSULTORIO_ADMIN, ADMINISTRATIVO, PROFESIONAL, PACIENTE) y cada familia
 * critica —personas, clinica, sesiones, cobros, caja, egresos, presentaciones, reportes,
 * colaboradores y auditoria— el codigo HTTP que la matriz pide.
 *
 * <p>Cada cuenta se registra, se loguea y elige el contexto por el camino real: el permiso sale
 * de la membership resuelta por la cadena entera, no de un actor armado a mano.
 *
 * <p>Las aserciones son blandas: un rol que no coincide no corta el recorrido, y el reporte final
 * nombra <b>cada</b> celda que diverge.
 */
class MatrizDePermisosIT extends BaseEscenarioDiferido {

	private static final String MOTIVO = "prueba sintetica de G-5";
	private static final String JUSTIFICACION = "Matriz de permisos G-5, dato sintetico";
	private static final long INEXISTENTE = 987_654_321L;

	private static final String OA = "ORG_ADMIN";
	private static final String CA = "CONSULTORIO_ADMIN";
	private static final String ADM = "ADMINISTRATIVO";
	private static final String PROF = "PROFESIONAL";
	private static final String PAC = "PACIENTE";

	@Autowired
	private MembershipService membershipService;

	private Sesion tenant;
	private final Map<String, String> tokens = new LinkedHashMap<>();
	private final Map<String, Long> memberships = new LinkedHashMap<>();
	private long persona;
	private long historia;

	@BeforeEach
	void sembrar() {
		tenant = altaCompleta("g5-matriz");
		tokens.put(OA, tenant.token());
		memberships.put(OA, jdbc.queryForObject(
				"SELECT id FROM membership WHERE organization_id = ? AND account_id = ?",
				Long.class, tenant.organizationId(), tenant.cuentaId()));
		for (String rol : List.of(CA, ADM, PROF, PAC)) {
			miembro("g5-" + rol.toLowerCase(), rol);
		}

		CoberturaFixtures datos = new CoberturaFixtures(jdbc);
		persona = datos.persona(tenant.organizationId(), "Sintetica", "Matriz", "39555111");
		datos.perfilPaciente(tenant.organizationId(), persona);

		// La historia la abre quien la matriz habilita a editarla: el profesional.
		Respuesta abierta = conJustificacion("PUT",
				"/api/v1/historias-clinicas/por-persona/" + persona, tokens.get(PROF), "{}");
		assertThat(abierta.status()).as("el profesional abre la historia: %s", abierta.body())
				.isIn(200, 201);
		historia = abierta.json().get("id").asLong();
	}

	@Test
	@DisplayName("G5-M1 cada familia critica responde a cada rol lo que la matriz le asigna")
	void la_matriz_se_cumple_endpoint_por_endpoint() {
		String c = "/api/v1/consultorios/" + tenant.consultorioId();
		String o = "/api/v1/organizations/" + tenant.organizationId();
		String periodo = "?from=" + Instant.now().minus(1, ChronoUnit.DAYS)
				+ "&to=" + Instant.now().plus(1, ChronoUnit.DAYS);

		List<Celda> matriz = new ArrayList<>();

		// Gestionar paciente / paciente:read (DP-22): todo el personal; PACIENTE no.
		matriz.add(lectura("personas: padron", "/api/v1/personas", 200, OA, CA, ADM, PROF));
		matriz.add(lectura("personas: ficha", "/api/v1/personas/" + persona, 200, OA, CA, ADM, PROF));

		// Ver Historia Clinica: solo PROFESIONAL por base. ORG_ADMIN "No por defecto",
		// CONSULTORIO_ADMIN "Segun rol clinico" (grant), ADMINISTRATIVO "Limitado" (cerrado).
		matriz.add(clinica("clinica: historia",
				"/api/v1/historias-clinicas/por-persona/" + persona));
		matriz.add(clinica("clinica: timeline",
				"/api/v1/historias-clinicas/" + historia + "/timeline"));
		matriz.add(clinica("clinica: casos",
				"/api/v1/historias-clinicas/" + historia + "/casos"));

		// Registrar Sesion: solo PROFESIONAL. La sesion no existe: quien tiene el permiso pasa
		// y recibe el 404 del dato; quien no, el 403 antes de mirarlo.
		matriz.add(lectura("sesiones: una sesion", c + "/sesiones/" + INEXISTENTE, 404, PROF));

		// Registrar Cobro: ORG_ADMIN, CONSULTORIO_ADMIN y ADMINISTRATIVO.
		matriz.add(lectura("cobros: deuda de la persona",
				c + "/obligaciones?personaId=" + persona, 200, OA, CA, ADM));
		matriz.add(lectura("cobros: cobros de la persona",
				c + "/cobros?personaId=" + persona, 200, OA, CA, ADM));

		// Operar Caja: los mismos tres. Los egresos reusan caja:operate.
		matriz.add(lectura("caja: jornadas", c + "/caja/jornadas", 200, OA, CA, ADM));
		matriz.add(lectura("caja: movimientos", c + "/caja/movimientos", 200, OA, CA, ADM));
		matriz.add(lectura("egresos", c + "/egresos", 200, OA, CA, ADM));

		// Presentaciones: cobro:register.
		matriz.add(lectura("presentaciones", c + "/presentaciones", 200, OA, CA, ADM));

		// Ver Reportes (DP-15): todo el personal, con el recorte de cada seccion; PACIENTE no.
		matriz.add(lectura("reportes: catalogo", c + "/reportes", 200, OA, CA, ADM, PROF));

		// colaborador:read: todo el personal; PACIENTE no.
		matriz.add(lectura("colaboradores: listado", o + "/memberships", 200, OA, CA, ADM, PROF));

		// auditoria:read: ORG_ADMIN (organizacion) y CONSULTORIO_ADMIN (su sede).
		matriz.add(lectura("auditoria", o + "/audit-events" + periodo, 200, OA, CA));

		SoftAssertions celdas = new SoftAssertions();
		for (Celda celda : matriz) {
			for (Map.Entry<String, String> rol : tokens.entrySet()) {
				int esperado = celda.permitidos().contains(rol.getKey()) ? celda.siPermitido() : 403;
				Respuesta respuesta = celda.justificar()
						? conJustificacion("GET", celda.ruta(), rol.getValue(), null)
						: get(celda.ruta(), rol.getValue());
				celdas.assertThat(respuesta.status())
						.as("%s [%s] %s -> %s", celda.familia(), rol.getKey(), celda.ruta(),
								respuesta.body())
						.isEqualTo(esperado);
				celdas.assertThat(respuesta.body())
						.as("%s [%s]: ninguna respuesta filtra internals", celda.familia(), rol.getKey())
						.doesNotContain("com.akine")
						.doesNotContain("org.springframework");
			}
		}
		celdas.assertAll();
	}

	@Test
	@DisplayName("G5-M2 gestionar colaboradores: solo ORG_ADMIN y CONSULTORIO_ADMIN otorgan permisos")
	void solo_los_administradores_otorgan() {
		String grants = "/api/v1/organizations/" + tenant.organizationId()
				+ "/memberships/" + memberships.get(CA) + "/grants";
		String cuerpo = grant("auditoria:read-clinica");

		SoftAssertions celdas = new SoftAssertions();
		for (String rol : List.of(ADM, PROF, PAC)) {
			Respuesta respuesta = post(grants, tokens.get(rol), cuerpo);
			celdas.assertThat(respuesta.status())
					.as("[%s] otorgar un permiso: %s", rol, respuesta.body())
					.isEqualTo(403);
		}
		Respuesta admin = post(grants, tokens.get(OA), cuerpo);
		celdas.assertThat(admin.status()).as("[ORG_ADMIN] otorga: %s", admin.body()).isIn(200, 201);
		celdas.assertAll();
	}

	@Test
	@DisplayName("G5-M3 una celda 'No' de la matriz no se puede otorgar por grant")
	void una_celda_no_no_es_otorgable() {
		String o = "/api/v1/organizations/" + tenant.organizationId() + "/memberships/";
		String token = tokens.get(OA);

		SoftAssertions celdas = new SoftAssertions();
		// "Editar Historia Clinica": ORG_ADMIN "No", ADMINISTRATIVO "No", PACIENTE "No".
		rechazado(celdas, post(o + memberships.get(OA) + "/grants", token, grant("hc:write")),
				"hc:write a ORG_ADMIN");
		rechazado(celdas, post(o + memberships.get(ADM) + "/grants", token, grant("hc:write")),
				"hc:write a ADMINISTRATIVO");
		rechazado(celdas, post(o + memberships.get(PAC) + "/grants", token, grant("hc:write")),
				"hc:write a PACIENTE");
		// "Ver Historia Clinica": ADMINISTRATIVO "Limitado" (solo metadatos, sin codigo propio) y
		// PACIENTE "Propia autorizada" (alcance OWN, sin implementar).
		rechazado(celdas, post(o + memberships.get(ADM) + "/grants", token, grant("hc:read")),
				"hc:read a ADMINISTRATIVO");
		rechazado(celdas, post(o + memberships.get(PAC) + "/grants", token, grant("hc:read")),
				"hc:read a PACIENTE");
		// "Gestionar paciente": PACIENTE "Propio" (OWN).
		rechazado(celdas, post(o + memberships.get(PAC) + "/grants", token, grant("paciente:manage")),
				"paciente:manage a PACIENTE");
		// auditoria:read-clinica: la §6 solo la da por grant a los dos administradores.
		rechazado(celdas, post(o + memberships.get(PROF) + "/grants", token,
				grant("auditoria:read-clinica")), "auditoria:read-clinica a PROFESIONAL");
		celdas.assertAll();

		long filas = jdbc.queryForObject(
				"SELECT COUNT(*) FROM membership_grant WHERE membership_id IN (?, ?, ?, ?)",
				Long.class, memberships.get(OA), memberships.get(ADM), memberships.get(PAC),
				memberships.get(PROF));
		assertThat(filas).as("ningun rechazo deja una fila de grant").isZero();

		// Y las celdas que la matriz si traduce a grant se siguen otorgando.
		SoftAssertions validos = new SoftAssertions();
		for (Map.Entry<String, String> caso : Map.of(
				"hc:read", OA, "hc:write", CA, "paciente:manage", PROF,
				"auditoria:read-clinica", OA).entrySet()) {
			Respuesta respuesta = post(o + memberships.get(caso.getValue()) + "/grants", token,
					grant(caso.getKey()));
			validos.assertThat(respuesta.status())
					.as("%s a %s: %s", caso.getKey(), caso.getValue(), respuesta.body())
					.isIn(200, 201);
		}
		validos.assertAll();
	}

	@Test
	@DisplayName("G5-M4 con el grant que la matriz admite, el CONSULTORIO_ADMIN lee la historia")
	void el_grant_clinico_habilita_al_consultorio_admin() {
		String ruta = "/api/v1/historias-clinicas/por-persona/" + persona;
		assertThat(conJustificacion("GET", ruta, tokens.get(CA), null).status()).isEqualTo(403);

		Respuesta otorgado = post("/api/v1/organizations/" + tenant.organizationId()
				+ "/memberships/" + memberships.get(CA) + "/grants", tokens.get(OA), grant("hc:read"));
		assertThat(otorgado.status()).as(otorgado.body()).isIn(200, 201);

		Respuesta lectura = conJustificacion("GET", ruta, tokens.get(CA), null);
		assertThat(lectura.status()).as(lectura.body()).isEqualTo(200);
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private record Celda(String familia, String ruta, int siPermitido, List<String> permitidos,
			boolean justificar) {
	}

	private static Celda lectura(String familia, String ruta, int siPermitido, String... permitidos) {
		return new Celda(familia, ruta, siPermitido, List.of(permitidos), false);
	}

	private static Celda clinica(String familia, String ruta) {
		return new Celda(familia, ruta, 200, List.of(PROF), true);
	}

	private static String grant(String codigo) {
		return "{\"permissionCode\":\"" + codigo + "\",\"reason\":\"" + MOTIVO + "\"}";
	}

	private static void rechazado(SoftAssertions celdas, Respuesta respuesta, String que) {
		celdas.assertThat(respuesta.status()).as("%s: %s", que, respuesta.body()).isEqualTo(400);
	}

	private Respuesta conJustificacion(String metodo, String ruta, String token, String cuerpo) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri(ruta))
				.header("Authorization", "Bearer " + token)
				.header("X-Justificacion-Acceso", JUSTIFICACION)
				.header("Content-Type", "application/json")
				.method(metodo, cuerpo == null
						? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofString(cuerpo));
		return ejecutar(builder.build());
	}

	private void miembro(String etiqueta, String rol) {
		Sesion cuenta = altaCompleta(etiqueta);
		long id = membershipService.createDirect(
				tenant.cuentaId(), false, tenant.organizationId(),
				new DirectMembershipCommand(cuenta.cuentaId(), tenant.consultorioId(), rol, MOTIVO));
		memberships.put(rol, id);
		tokens.put(rol, seleccionarContexto(login(cuenta.email()),
				tenant.organizationId(), tenant.consultorioId()));
	}
}
