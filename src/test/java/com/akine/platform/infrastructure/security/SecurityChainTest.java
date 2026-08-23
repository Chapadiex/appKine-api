package com.akine.platform.infrastructure.security;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akine.platform.infrastructure.config.SecurityConfig;
import com.akine.platform.spi.security.AccessTokenClaims;
import com.akine.platform.spi.security.AccessTokenScope;
import com.akine.platform.spi.security.AccessTokenVerifier;

import static com.akine.PerfilesDeTest.SOLO_SLICE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La cadena real de la aplicacion, ejercitada de punta a punta.
 *
 * <p>Lo que se prueba no es "que haya seguridad", sino las cuatro decisiones que rompen algo si
 * se invierten: que <b>autenticado sea el default</b>, que la lista de rutas publicas sea
 * efectivamente publica, que un token roto de <b>401</b> y un permiso faltante de <b>403</b>, y
 * que ambos salgan en formato Problem Details, que es por donde ramifica el frontend.
 *
 * <p>Se usa una ruta inexistente para verificar lo publico: si la cadena la deja pasar, el
 * {@code DispatcherServlet} responde 404; si la protege, responde 401 antes de llegar. La
 * diferencia entre 404 y 401 es exactamente la pregunta que se quiere hacer, y no hace falta
 * publicar un controller de mentira por cada ruta de la lista.
 */
@WebMvcTest(controllers = SecurityChainTest.ControllerProtegido.class)
@Import({SecurityChainTest.ControllerProtegido.class, SecurityConfig.class,
		SecurityFiltersConfig.class})
// "local" es obligatorio: desde el arreglo del gate de documentacion, la publicacion de
// /v3/api-docs y /swagger-ui exige un perfil de DESARROLLO declarado por nombre, y todo lo
// demas queda cerrado. Ver PerfilesDeEjecucion y SecurityChainPerfilDesconocidoTest.
@ActiveProfiles({"local", SOLO_SLICE})
class SecurityChainTest {

	private static final String BEARER_VALIDO = "Bearer token-sintetico-valido";

	@Autowired
	private MockMvc mockMvc;

	/**
	 * El verificador se simula: quien lo implementa es {@code identity.infrastructure.JwtEmitter}
	 * y su criptografia tiene sus propios tests. Aca lo que se prueba es como reacciona la
	 * cadena a cada uno de sus tres resultados posibles.
	 */
	@MockitoBean
	private AccessTokenVerifier accessTokenVerifier;

	private void elTokenEsValido(AccessTokenScope alcance) {
		given(accessTokenVerifier.verify(any())).willReturn(Optional.of(new AccessTokenClaims(
				42L, "jti-1", alcance,
				alcance == AccessTokenScope.CONTEXT ? 10L : null,
				alcance == AccessTokenScope.CONTEXT ? 20L : null,
				null, "familia-1", Instant.now(), Instant.now().plusSeconds(600))));
	}

	// =================================================================================
	// Autenticado es el default
	// =================================================================================

	@Test
	@DisplayName("sin token, una ruta de negocio responde 401 en Problem Details")
	void sin_token_responde_401() throws Exception {
		mockMvc.perform(get("/api/v1/probe"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/unauthorized"))
				.andExpect(jsonPath("$.status").value(401))
				.andExpect(jsonPath("$.instance").value("/api/v1/probe"));
	}

	@Test
	@DisplayName("con un token que no verifica, 401 y no se llega al controller")
	void con_un_token_invalido_responde_401() throws Exception {
		given(accessTokenVerifier.verify(any())).willReturn(Optional.empty());

		mockMvc.perform(get("/api/v1/probe").header("Authorization", "Bearer basura"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/unauthorized"))
				// El detalle es fijo: nunca dice si fallo la firma, el vencimiento o el alcance.
				.andExpect(jsonPath("$.detail")
						.value("La credencial presentada no es valida o expiro. "
								+ "Inicie sesion nuevamente."));
	}

	@Test
	@DisplayName("un Authorization que no es Bearer se trata como ausencia de credencial")
	void un_esquema_que_no_es_bearer_es_ausencia() throws Exception {
		mockMvc.perform(get("/api/v1/probe").header("Authorization", "Basic dXN1YXJpbzpjbGF2ZQ=="))
				.andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("con un token valido el request llega al controller")
	void con_un_token_valido_se_pasa() throws Exception {
		elTokenEsValido(AccessTokenScope.PRE_CONTEXT);

		mockMvc.perform(get("/api/v1/probe").header("Authorization", BEARER_VALIDO))
				.andExpect(status().isOk())
				.andExpect(content().string("42"));
	}

	@Test
	@DisplayName("el esquema Bearer se reconoce sin importar mayusculas")
	void el_esquema_bearer_es_insensible_a_mayusculas() throws Exception {
		elTokenEsValido(AccessTokenScope.PRE_CONTEXT);

		mockMvc.perform(get("/api/v1/probe").header("Authorization", "bEaReR abc"))
				.andExpect(status().isOk());
	}

	// =================================================================================
	// 401 no es 403
	// =================================================================================

	@Test
	@DisplayName("autenticado sin permiso es 403, jamas 401: un 401 desloguearia al usuario")
	void autenticado_sin_permiso_es_403() throws Exception {
		elTokenEsValido(AccessTokenScope.CONTEXT);

		mockMvc.perform(get("/api/v1/probe/denegado").header("Authorization", BEARER_VALIDO))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"))
				.andExpect(jsonPath("$.status").value(403));
	}

	// =================================================================================
	// Rutas publicas
	// =================================================================================

	@Test
	@DisplayName("los endpoints de autenticacion son publicos: la cadena no los rechaza")
	void los_endpoints_de_auth_son_publicos() throws Exception {
		// Todavia no existen (los publica identity.api en la ola siguiente). Que la respuesta
		// NO sea 401 ni 403 prueba lo unico que se quiere probar: que la cadena los deja pasar
		// y el rechazo, si lo hay, es del enrutamiento y no de la seguridad.
		for (String ruta : new String[]{
				"/api/v1/auth/login",
				"/api/v1/auth/refresh",
				"/api/v1/auth/logout",
				"/api/v1/auth/register",
				"/api/v1/auth/activate",
				"/api/v1/auth/activation/resend",
				"/api/v1/auth/password-reset",
				"/api/v1/auth/password-reset/confirm"}) {
			laCadenaLoDejaPasar(post(ruta));
		}
	}

	@Test
	@DisplayName("el health de Actuator es publico y el resto de Actuator no")
	void el_health_es_publico_y_el_resto_no() throws Exception {
		laCadenaLoDejaPasar(get("/actuator/health"));
		mockMvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("el contrato tecnico de version es publico")
	void la_version_es_publica() throws Exception {
		laCadenaLoDejaPasar(get("/api/v1/version"));
	}

	@Test
	@DisplayName("con un perfil de desarrollo activo la documentacion del contrato es publica")
	void la_documentacion_es_publica_en_desarrollo() throws Exception {
		laCadenaLoDejaPasar(get("/v3/api-docs"));
		laCadenaLoDejaPasar(get("/swagger-ui.html"));
	}

	/**
	 * Afirma que la cadena de seguridad no rechazo el request.
	 *
	 * <p>No se puede exigir un 200 ni un 404 concreto: en un slice, una ruta sin controller
	 * termina en el manejo de recurso no encontrado, que segun la configuracion del slice sale
	 * como 404 o como 500. Lo unico relevante —y lo unico estable— es que no sea 401 ni 403,
	 * que son las dos respuestas que produce esta cadena.
	 */
	private void laCadenaLoDejaPasar(
			org.springframework.test.web.servlet.RequestBuilder request) throws Exception {
		int status;
		try {
			status = mockMvc.perform(request).andReturn().getResponse().getStatus();
		} catch (Exception noHayHandler) {
			// El slice propaga la excepcion de "no hay handler": llego al enrutamiento, o sea
			// que la seguridad ya lo habia dejado pasar.
			return;
		}
		org.assertj.core.api.Assertions.assertThat(status).isNotIn(401, 403);
	}

	@Test
	@DisplayName("el preflight de CORS pasa sin credenciales")
	void el_preflight_pasa_sin_credenciales() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.options("/api/v1/probe")
						.header("Origin", "http://localhost:4200")
						.header("Access-Control-Request-Method", "GET"))
				.andExpect(status().isOk());
	}

	/**
	 * Controller minimo para tener una ruta de negocio real que proteger.
	 *
	 * <p>Vive en las fuentes de test, asi que los tests de arquitectura no lo ven
	 * ({@code ImportOption.DoNotIncludeTests}) y no aparece en el contrato OpenAPI.
	 */
	@RestController
	@Profile(SOLO_SLICE)
	@RequestMapping("/api/v1/probe")
	static class ControllerProtegido {

		@GetMapping
		String protegido() {
			return "42";
		}

		/** Simula un rechazo de permiso nacido adentro del servicio, ya autenticado. */
		@GetMapping("/denegado")
		String denegado() {
			throw new AccessDeniedException("no alcanza");
		}
	}
}
