package com.akine.diferidos;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.akine.organization.application.MembershipService;
import com.akine.organization.spi.DirectMembershipCommand;
import com.akine.person.support.CoberturaFixtures;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AKINE-DU-6 (DP-22) contra MySQL real y por HTTP: {@code paciente:read} cierra el padron al rol
 * {@code PACIENTE} y lo deja abierto para el personal.
 *
 * <h2>Por que por HTTP y no por el servicio</h2>
 *
 * <p>El hueco que DU-6 cierra no era de un servicio sino de la cadena entera: el filtro de tenant
 * dejaba pasar a cualquier membership vigente y el servicio no preguntaba nada mas. Probarlo con
 * un {@code OperatingActor} armado a mano saltearia justo las dos piezas que deciden —la
 * seleccion de contexto y la resolucion de la membership con su rol—.
 *
 * <h2>El escenario</h2>
 *
 * <p>Un tenant con su fundador ({@code ORG_ADMIN}) y tres cuentas mas dadas de alta en la misma
 * sede: una {@code PACIENTE}, una {@code ADMINISTRATIVO} y una {@code PROFESIONAL}. Cada una se
 * loguea y elige el contexto de ese tenant por el camino real. Las dos ultimas existen para probar
 * que el alcance de consultorio pasa —el permiso se evalua con la sede del contexto—, que es lo
 * que se rompe si alguien "simplifica" la consulta sin sede.
 */
class PacienteReadIT extends BaseEscenarioDiferido {

	private static final String MOTIVO = "prueba sintetica de DU-6";
	private static final LocalDate HOY = LocalDate.of(2027, 6, 15);
	private static final String AFILIADO = "62000777000";
	private static final long INEXISTENTE = 987_654_321L;

	@Autowired
	private MembershipService membershipService;

	private Sesion tenant;
	private long persona;
	private long cobertura;

	@BeforeEach
	void sembrar() {
		tenant = altaCompleta("du6-staff");
		CoberturaFixtures datos = new CoberturaFixtures(jdbc);
		persona = datos.persona(tenant.organizationId(), "Sintetica", "Paula", "39000111");
		datos.perfilPaciente(tenant.organizationId(), persona);
		cobertura = datos.cobertura(tenant.organizationId(), persona,
				datos.planNuevo(tenant.organizationId()), AFILIADO, HOY.minusYears(1), null, true, null);
	}

	@Test
	@DisplayName("DU6-E1 una membership PACIENTE recibe 403 en todas las lecturas de person")
	void paciente_recibe_403_en_todo() {
		String token = miembro("du6-paciente", "PACIENTE");

		lecturas().forEach((nombre, ruta) -> {
			Respuesta respuesta = get(ruta, token);
			assertThat(respuesta.status())
					.as("%s (%s) para PACIENTE: %s", nombre, ruta, respuesta.body())
					.isEqualTo(403);
			assertProblemaLimpio(respuesta);
		});
	}

	@Test
	@DisplayName("DU6-E2 el ORG_ADMIN lee el padron, la ficha, el 360, coberturas, documentos y la busqueda")
	void org_admin_lee_todo() {
		assertLeeTodo("ORG_ADMIN", tenant.token());
	}

	@Test
	@DisplayName("DU6-E3 ADMINISTRATIVO y PROFESIONAL, con alcance de sede, tambien leen")
	void staff_de_sede_lee_todo() {
		assertLeeTodo("ADMINISTRATIVO", miembro("du6-recepcion", "ADMINISTRATIVO"));
		assertLeeTodo("PROFESIONAL", miembro("du6-profesional", "PROFESIONAL"));
	}

	@Test
	@DisplayName("DU6-E4 el 360 del personal trae la seccion coberturas y no la declara omitida")
	void el_360_trae_coberturas() {
		Respuesta resumen = get("/api/v1/personas/" + persona + "/resumen", tenant.token());

		assertThat(resumen.status()).isEqualTo(200);
		assertThat(resumen.body()).contains("\"coberturas\"");
		assertThat(resumen.json().get("seccionesOmitidas").toString())
				.doesNotContain("coberturas");
	}

	@Test
	@DisplayName("DU6-E5 cross-tenant sigue siendo 404 y no 403: el personal de otro centro no ve a la persona")
	void cross_tenant_sigue_404() {
		Sesion ajeno = altaCompleta("du6-ajeno");

		Respuesta ficha = get("/api/v1/personas/" + persona, ajeno.token());
		assertThat(ficha.status()).as(ficha.body()).isEqualTo(404);
		Respuesta coberturas = get("/api/v1/personas/" + persona + "/coberturas", ajeno.token());
		assertThat(coberturas.status()).as(coberturas.body()).isEqualTo(404);
		Respuesta busqueda = get("/api/v1/personas?q=" + AFILIADO, ajeno.token());
		assertThat(busqueda.status()).isEqualTo(200);
		assertThat(busqueda.json().get("content").size()).isZero();
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	/**
	 * Las lecturas de {@code person} que DU-6 cierra. Las que apuntan a un id inexistente estan
	 * para el PACIENTE —el 403 no puede depender de que el dato exista— y para el personal, que
	 * pasa el permiso y recibe el 404 del dato.
	 */
	private Map<String, String> lecturas() {
		String p = "/api/v1/personas/" + persona;
		Map<String, String> rutas = new LinkedHashMap<>();
		rutas.put("padron", "/api/v1/personas");
		rutas.put("busqueda por afiliado", "/api/v1/personas?q=" + AFILIADO);
		rutas.put("ficha", p);
		rutas.put("360", p + "/resumen");
		rutas.put("coberturas", p + "/coberturas");
		rutas.put("cobertura para la atencion", p + "/coberturas/seleccion");
		rutas.put("documentos", p + "/adjuntos");
		rutas.put("ordenes", p + "/ordenes");
		rutas.put("autorizaciones", p + "/autorizaciones");
		rutas.put("autorizaciones elegibles", p + "/autorizaciones/elegibles");
		rutas.put("elegibilidad", p + "/elegibilidad?coberturaId=" + cobertura + "&practicaId=" + INEXISTENTE);
		rutas.put("cobertura aplicable", p + "/cobertura-aplicable?ofertaId=" + INEXISTENTE);
		rutas.put("descarga de adjunto", p + "/adjuntos/" + INEXISTENTE + "/contenido");
		rutas.put("autorizacion", p + "/autorizaciones/" + INEXISTENTE);
		rutas.put("saldo de autorizacion", "/api/v1/autorizaciones/" + INEXISTENTE + "/saldo");
		rutas.put("ledger de autorizacion", "/api/v1/autorizaciones/" + INEXISTENTE + "/movimientos");
		rutas.put("alertas de autorizacion", "/api/v1/autorizaciones/" + INEXISTENTE + "/alertas");
		return rutas;
	}

	private void assertLeeTodo(String rol, String token) {
		lecturas().forEach((nombre, ruta) -> {
			Respuesta respuesta = get(ruta, token);
			assertThat(respuesta.status())
					.as("%s (%s) para %s: %s", nombre, ruta, rol, respuesta.body())
					.isNotEqualTo(403)
					.isIn(200, 404);
			if (!ruta.contains(String.valueOf(INEXISTENTE))) {
				assertThat(respuesta.status())
						.as("%s sobre datos sembrados para %s: %s", nombre, rol, respuesta.body())
						.isEqualTo(200);
			}
		});

		Respuesta busqueda = get("/api/v1/personas?q=" + AFILIADO, token);
		assertThat(busqueda.json().get("content").get(0).get("id").asLong())
				.as("la busqueda por afiliado encuentra a la persona para %s", rol)
				.isEqualTo(persona);
	}

	/**
	 * Una cuenta nueva con membership {@code rol} en la sede del tenant, logueada y con el contexto
	 * de ese tenant elegido por el camino real.
	 */
	private String miembro(String etiqueta, String rol) {
		Sesion cuenta = altaCompleta(etiqueta);
		membershipService.createDirect(
				tenant.cuentaId(), false, tenant.organizationId(),
				new DirectMembershipCommand(cuenta.cuentaId(), tenant.consultorioId(), rol, MOTIVO));
		return seleccionarContexto(login(cuenta.email()), tenant.organizationId(), tenant.consultorioId());
	}
}
