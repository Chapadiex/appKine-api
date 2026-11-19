package com.akine.identity.api;

import com.akine.identity.application.CuentasDePlataformaService;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP de las cuentas por estado del panel de plataforma (19/11/2026). */
@WebMvcTest(CuentasDePlataformaController.class)
@Import(IdentityApiSliceSecurityConfig.class)
class CuentasDePlataformaControllerTest {

	@Autowired private MockMvc mockMvc;
	@MockitoBean private CuentasDePlataformaService service;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("La plataforma lee las cuentas por estado")
	void la_plataforma_lee_las_cuentas() throws Exception {
		given(service.resumen()).willReturn(new CuentasDePlataformaService.Resumen(40, 3, 1));

		mockMvc.perform(get("/api/v1/platform/resumen/cuentas").with(IdentityApiActors.platformAdmin(1L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.activas").value(40))
				.andExpect(jsonPath("$.pendientesDeActivar").value(3))
				.andExpect(jsonPath("$.bloqueadas").value(1));
	}

	@Test
	@DisplayName("Una cuenta sin rol de plataforma recibe 403")
	void sin_rol_403() throws Exception {
		mockMvc.perform(get("/api/v1/platform/resumen/cuentas").with(IdentityApiActors.miembro(3L)))
				.andExpect(status().isForbidden());

		verifyNoInteractions(service);
	}
}
