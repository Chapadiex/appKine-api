package com.akine.platform.api;

import com.akine.platform.application.VersionService;
import com.akine.platform.infrastructure.config.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.akine.platform.domain.BuildVersion;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Smoke del contrato tecnico de versionado (AKINE-00.01).
 *
 * <p>Slice web: no levanta base de datos ni contexto completo.
 */
@WebMvcTest(VersionController.class)
@Import(SecurityConfig.class)
class VersionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private VersionService versionService;

	@Test
	@DisplayName("GET /api/v1/version devuelve la identidad tecnica del backend")
	void devuelve_la_version() throws Exception {
		given(versionService.current())
				.willReturn(new BuildVersion("akine-api", "0.0.1-SNAPSHOT", "0.1.0"));

		mockMvc.perform(get("/api/v1/version"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.application").value("akine-api"))
				.andExpect(jsonPath("$.version").value("0.0.1-SNAPSHOT"))
				.andExpect(jsonPath("$.contract").value("0.1.0"));
	}

	@Test
	@DisplayName("El endpoint de version es publico: no exige autenticacion")
	void es_publico() throws Exception {
		given(versionService.current())
				.willReturn(new BuildVersion("akine-api", "0.0.1-SNAPSHOT", "0.1.0"));

		mockMvc.perform(get("/api/v1/version"))
				.andExpect(status().isOk());
	}
}
