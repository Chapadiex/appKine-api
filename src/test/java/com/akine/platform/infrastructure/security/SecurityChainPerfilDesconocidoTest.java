package com.akine.platform.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akine.platform.infrastructure.config.SecurityConfig;
import com.akine.platform.spi.security.AccessTokenVerifier;

import static com.akine.PerfilesDeTest.SOLO_SLICE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La misma cadena con un perfil que <b>nadie previo</b>.
 *
 * <h2>Por que este test es el que importa</h2>
 *
 * <p>{@link SecurityChainProduccionTest} prueba que con {@code prod} la documentacion se cierra,
 * y eso ya pasaba antes: la regla era una lista negra de tres nombres —{@code prod},
 * {@code produccion}, {@code production}— y todo lo demas se consideraba desarrollo. El agujero
 * no estaba en los tres nombres previstos sino en los que no lo estaban: un despliegue con perfil
 * {@code staging}, {@code docker}, {@code prd} o {@code k8s} publicaba a cualquiera el mapa
 * completo de la API. Una lista negra de tres nombres falla del lado inseguro ante el primer
 * nombre que nadie escribio.
 *
 * <p>Por eso el perfil de este test es {@code staging}: un nombre real, plausible, y
 * deliberadamente ausente de toda lista. Con la regla invertida —desarrollo declarado por nombre
 * positivo, todo lo demas cerrado— la respuesta correcta es la misma que en produccion. Este test
 * se pone en rojo si alguien vuelve a poner la regla al reves.
 */
@WebMvcTest(controllers = SecurityChainPerfilDesconocidoTest.ControllerProtegido.class)
@Import({SecurityChainPerfilDesconocidoTest.ControllerProtegido.class, SecurityConfig.class,
		SecurityFiltersConfig.class})
@ActiveProfiles({"staging", SOLO_SLICE})
class SecurityChainPerfilDesconocidoTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AccessTokenVerifier accessTokenVerifier;

	@Test
	@DisplayName("un perfil que no es de desarrollo cierra la documentacion, aunque no se llame prod")
	void un_perfil_desconocido_cierra_la_documentacion() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("y el health sigue publico: cerrar la documentacion no rompe los probes")
	void el_health_sigue_publico() throws Exception {
		mockMvc.perform(get("/actuator/health"))
				.andExpect(status().is(not401ni403()));
	}

	/** Cualquier cosa menos 401 y 403. En un slice el health no esta autoconfigurado. */
	private static org.hamcrest.Matcher<Integer> not401ni403() {
		return org.hamcrest.Matchers.not(org.hamcrest.Matchers.isOneOf(401, 403));
	}

	@RestController
	@Profile(SOLO_SLICE)
	@RequestMapping("/api/v1/probe")
	static class ControllerProtegido {

		@GetMapping
		String protegido() {
			return "42";
		}
	}
}
