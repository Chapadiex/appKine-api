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
import com.akine.platform.spi.security.AccessTokenClaims;
import com.akine.platform.spi.security.AccessTokenScope;
import com.akine.platform.spi.security.AccessTokenVerifier;

import java.time.Instant;
import java.util.Optional;

import static com.akine.PerfilesDeTest.SOLO_SLICE;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La misma cadena, con un perfil de produccion activo.
 *
 * <p>Lo unico que cambia es la documentacion del contrato. Fuera de produccion es una
 * herramienta de trabajo; en produccion es un mapa completo de cada endpoint y cada campo del
 * sistema, servido a cualquiera que pase. Un {@code SecurityChainTest} que solo corriera con el
 * perfil por defecto no notaria nunca si esa rama se rompe.
 *
 * <p>El health sigue publico tambien en produccion: lo consultan los probes del orquestador, que
 * no tienen sesion.
 */
@WebMvcTest(controllers = SecurityChainProduccionTest.ControllerProtegido.class)
@Import({SecurityChainProduccionTest.ControllerProtegido.class, SecurityConfig.class,
		SecurityFiltersConfig.class})
@ActiveProfiles({"prod", SOLO_SLICE})
class SecurityChainProduccionTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AccessTokenVerifier accessTokenVerifier;

	/**
	 * El directorio del rol de plataforma tambien se simula.
	 *
	 * <p>Desde AKINE-01.03 la cadena lo consulta en cada request autenticado para saber si la
	 * cuenta administra la plataforma: ese dato ya no sale del claim del token (ADR-0020). Lo
	 * implementa {@code organization}, que este slice no levanta, asi que sin este doble el
	 * contexto no arranca. Por defecto Mockito devuelve {@code false}, que es fail-closed y es
	 * lo que estos tests necesitan.
	 */
	@MockitoBean
	private com.akine.platform.spi.tenant.PlatformRoleDirectory platformRoleDirectory;

	@Test
	@DisplayName("en produccion la documentacion del contrato no se publica")
	void la_documentacion_no_se_publica_en_produccion() throws Exception {
		// denyAll sobre un request anonimo sale por el punto de entrada de autenticacion: 401.
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("en produccion una ruta de negocio sigue exigiendo autenticacion")
	void una_ruta_de_negocio_sigue_protegida() throws Exception {
		mockMvc.perform(get("/api/v1/probe")).andExpect(status().isUnauthorized());
	}

	@Test
	@DisplayName("estar autenticado tampoco abre la documentacion: 403, no 401")
	void autenticado_recibe_403_no_401() throws Exception {
		// Es el unico camino que ejercita el AccessDeniedHandler de la cadena: un rechazo
		// nacido en la propia autorizacion, no en un controller. Y prueba la distincion que
		// mas importa: quien ya esta autenticado nunca recibe un 401, que lo deslogearia.
		given(accessTokenVerifier.verify(any())).willReturn(Optional.of(new AccessTokenClaims(
				42L, "jti", AccessTokenScope.PRE_CONTEXT, null, null, null, "familia-1",
				Instant.now(), Instant.now().plusSeconds(600))));

		mockMvc.perform(get("/v3/api-docs").header("Authorization", "Bearer valido"))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"))
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.instance").value("/v3/api-docs"));
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
