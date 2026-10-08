package com.akine.platform.infrastructure.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.akine.platform.infrastructure.config.SecurityConfig;
import com.akine.platform.infrastructure.security.SecurityFiltersConfig;
import com.akine.platform.spi.security.AccessTokenVerifier;
import com.akine.platform.spi.tenant.PlatformRoleDirectory;

import static com.akine.PerfilesDeTest.SOLO_SLICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La politica de acceso a {@code /actuator/prometheus} (G-4), ver
 * {@link MetricsScrapeSecurityConfig}.
 *
 * <p>El slice sirve la ruta con un controller de mentira: lo que se prueba es la cadena de
 * seguridad, no el actuator, que lo cubre {@code ObservabilidadIT}.
 */
class MetricsScrapeSecurityTest {

	private static final String TOKEN = "token-del-scraper-de-prueba-con-32-caracteres-o-mas";

	@Nested
	@WebMvcTest(controllers = ScrapeFalso.class)
	@Import({ScrapeFalso.class, SecurityConfig.class, SecurityFiltersConfig.class,
			MetricsScrapeSecurityConfig.class})
	@ActiveProfiles({"prod", SOLO_SLICE})
	@org.springframework.test.context.TestPropertySource(
			properties = "akine.observability.metrics.scrape-token=" + TOKEN)
	class ConTokenEnProduccion {

		@Autowired
		private MockMvc mockMvc;

		@MockitoBean
		private AccessTokenVerifier accessTokenVerifier;

		@MockitoBean
		private PlatformRoleDirectory platformRoleDirectory;

		@Test
		@DisplayName("con el token del scraper responde; sin el o con otro, 401")
		void exige_el_token_del_scraper() throws Exception {
			mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer " + TOKEN))
					.andExpect(status().isOk())
					.andExpect(content().string("metricas"));

			mockMvc.perform(get("/actuator/prometheus"))
					.andExpect(status().isUnauthorized());
			// Un bearer cualquiera —un JWT de usuario, por ejemplo— no abre el scrape: esta
			// cadena no tiene el filtro de JWT, solo compara contra el token configurado.
			mockMvc.perform(get("/actuator/prometheus").header("Authorization", "Bearer otro"))
					.andExpect(status().isUnauthorized());
		}
	}

	@Test
	@DisplayName("sin token: abierto en un perfil de desarrollo, cerrado en cualquier otro")
	void sin_token_depende_del_perfil() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/prometheus");

		MockEnvironment local = new MockEnvironment();
		local.setActiveProfiles("local");
		MockEnvironment produccion = new MockEnvironment();
		produccion.setActiveProfiles("prod");

		assertThat(new MetricsScrapeSecurityConfig("", local).autorizado(request)).isTrue();
		assertThat(new MetricsScrapeSecurityConfig("", produccion).autorizado(request)).isFalse();
		assertThat(new MetricsScrapeSecurityConfig("", new MockEnvironment()).autorizado(request))
				.as("sin perfil es produccion, no desarrollo")
				.isFalse();
	}

	@Test
	@DisplayName("con token configurado, ni siquiera el perfil local abre el scrape sin el")
	void el_token_manda_sobre_el_perfil() {
		MockEnvironment local = new MockEnvironment();
		local.setActiveProfiles("local");

		assertThat(new MetricsScrapeSecurityConfig(TOKEN, local)
				.autorizado(new MockHttpServletRequest("GET", "/actuator/prometheus"))).isFalse();
	}

	@Test
	@DisplayName("un token de menos de 32 caracteres impide el arranque")
	void token_corto_no_arranca() {
		assertThatThrownBy(() -> new MetricsScrapeSecurityConfig("corto", new MockEnvironment()))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("32");
	}

	@RestController
	@Profile(SOLO_SLICE)
	static class ScrapeFalso {

		@GetMapping("/actuator/prometheus")
		String scrape() {
			return "metricas";
		}
	}
}
