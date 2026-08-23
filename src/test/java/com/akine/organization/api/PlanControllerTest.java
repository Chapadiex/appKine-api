package com.akine.organization.api;

import com.akine.organization.application.PlanCatalogService;
import com.akine.organization.application.PlanLimitView;
import com.akine.organization.application.PlanView;
import com.akine.organization.spi.FeatureCode;
import com.akine.organization.spi.LimitCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP del catalogo comercial (RF-M01-002).
 *
 * <p>Slice web: no levanta base de datos ni contexto completo. La cadena de seguridad la monta
 * {@link ApiSliceSecurityConfig}, no la de la aplicacion.
 */
@WebMvcTest(PlanController.class)
@Import(ApiSliceSecurityConfig.class)
class PlanControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PlanCatalogService planCatalogService;

	@Test
	@DisplayName("El catalogo publica cada plan por su codigo, con limites y funcionalidades")
	void publica_el_catalogo_vigente() throws Exception {
		given(planCatalogService.catalog()).willReturn(List.of(
				new PlanView(
						1L,
						"BASICO",
						"Basico",
						List.of(new PlanLimitView(LimitCode.MAX_CONSULTORIOS, 3)),
						List.of(FeatureCode.NOTIFICACIONES_PACIENTE))));

		mockMvc.perform(get("/api/v1/plans"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].code").value("BASICO"))
				.andExpect(jsonPath("$[0].name").value("Basico"))
				.andExpect(jsonPath("$[0].limits[0].code").value("MAX_CONSULTORIOS"))
				.andExpect(jsonPath("$[0].limits[0].value").value(3))
				.andExpect(jsonPath("$[0].limits[0].unlimited").value(false))
				.andExpect(jsonPath("$[0].features[0]").value("NOTIFICACIONES_PACIENTE"));
	}

	@Test
	@DisplayName("El id interno del plan no se publica: el catalogo se referencia por codigo")
	void no_publica_el_id_interno() throws Exception {
		given(planCatalogService.catalog()).willReturn(List.of(
				new PlanView(42L, "PROFESIONAL", "Profesional", List.of(), List.of())));

		mockMvc.perform(get("/api/v1/plans"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").doesNotExist());
	}

	@Test
	@DisplayName("Un limite sin tope viaja como unlimited=true y value ausente, nunca como cero")
	void un_limite_sin_tope_viaja_como_ilimitado() throws Exception {
		given(planCatalogService.catalog()).willReturn(List.of(
				new PlanView(
						2L,
						"PROFESIONAL",
						"Profesional",
						List.of(new PlanLimitView(LimitCode.MAX_MIEMBROS_ACTIVOS, null)),
						List.of(FeatureCode.REPORTES_AVANZADOS))));

		mockMvc.perform(get("/api/v1/plans"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].limits[0].unlimited").value(true))
				.andExpect(jsonPath("$[0].limits[0].value").doesNotExist());
	}

	@Test
	@DisplayName("Sin planes contratables responde una lista vacia, no un error")
	void catalogo_vacio_no_es_error() throws Exception {
		given(planCatalogService.catalog()).willReturn(List.of());

		mockMvc.perform(get("/api/v1/plans"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	@DisplayName("El catalogo no depende del contexto de trabajo: los planes no son de un tenant")
	void no_exige_contexto_de_tenant() throws Exception {
		given(planCatalogService.catalog()).willReturn(List.of());

		mockMvc.perform(get("/api/v1/plans").with(ApiActors.miembro(7L)))
				.andExpect(status().isOk());
	}
}
