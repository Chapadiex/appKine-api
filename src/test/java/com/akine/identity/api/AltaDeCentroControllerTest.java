package com.akine.identity.api;

import com.akine.identity.application.AltaDeCentroPlataformaService;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.AltaDeCentro;
import com.akine.organization.spi.Servicio;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP del alta de un centro desde la consola de plataforma (18/11/2026). */
@WebMvcTest(AltaDeCentroController.class)
@Import(IdentityApiSliceSecurityConfig.class)
class AltaDeCentroControllerTest {

	private static final String RUTA = "/api/v1/platform/organizations";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AltaDeCentroPlataformaService service;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("La plataforma da de alta el centro: 201 con centro, sede e invitacion")
	void alta_201() throws Exception {
		given(service.darDeAlta(any(), eq("duenio@centro.test")))
				.willReturn(new AltaDeCentroPlataformaService.Resultado(7L, 70L, 700L, "duenio@centro.test"));

		mockMvc.perform(post(RUTA).with(IdentityApiActors.platformAdmin(1L))
						.contentType(MediaType.APPLICATION_JSON).content(cuerpo("[\"KINESIOLOGIA\"]", "[\"IA\"]")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.organizationId").value(7))
				.andExpect(jsonPath("$.consultorioId").value(70))
				.andExpect(jsonPath("$.invitacionId").value(700))
				.andExpect(jsonPath("$.emailDuenio").value("duenio@centro.test"));

		ArgumentCaptor<AltaDeCentro.Comando> comando = ArgumentCaptor.forClass(AltaDeCentro.Comando.class);
		verify(service).darDeAlta(comando.capture(), eq("duenio@centro.test"));
		assertThat(comando.getValue().servicios()).containsExactly(Servicio.KINESIOLOGIA);
		assertThat(comando.getValue().adicionales()).containsExactly(Adicional.IA);
		assertThat(comando.getValue().prueba()).isTrue();
		assertThat(comando.getValue().solicitudId()).isEqualTo(5L);
	}

	@Test
	@DisplayName("Sin adicionales la contratacion nace sin ninguno")
	void sin_adicionales() throws Exception {
		given(service.darDeAlta(any(), any()))
				.willReturn(new AltaDeCentroPlataformaService.Resultado(7L, 70L, 700L, "duenio@centro.test"));

		mockMvc.perform(post(RUTA).with(IdentityApiActors.platformAdmin(1L))
						.contentType(MediaType.APPLICATION_JSON).content(cuerpo("[\"ACTIVIDADES\"]", "[]")))
				.andExpect(status().isCreated());

		ArgumentCaptor<AltaDeCentro.Comando> comando = ArgumentCaptor.forClass(AltaDeCentro.Comando.class);
		verify(service).darDeAlta(comando.capture(), any());
		assertThat(comando.getValue().adicionales()).isEmpty();
	}

	@Test
	@DisplayName("Una cuenta sin rol de plataforma recibe 403 y no se crea nada")
	void sin_rol_403() throws Exception {
		mockMvc.perform(post(RUTA).with(IdentityApiActors.miembro(3L))
						.contentType(MediaType.APPLICATION_JSON).content(cuerpo("[\"KINESIOLOGIA\"]", "[]")))
				.andExpect(status().isForbidden());

		verifyNoInteractions(service);
	}

	private static String cuerpo(String servicios, String adicionales) {
		return """
				{"nombreCentro":"Centro Norte","emailDuenio":"duenio@centro.test","planCode":"BASE",
				 "servicios":%s,"adicionales":%s,"prueba":true,"solicitudId":5}"""
				.formatted(servicios, adicionales);
	}
}
