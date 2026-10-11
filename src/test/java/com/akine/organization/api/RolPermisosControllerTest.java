package com.akine.organization.api;

import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.MembershipService;
import com.akine.organization.application.OperatingActor;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.UnknownPermissionCodeException;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de los permisos otorgables por rol. La regla vive en
 * {@code MembershipService.permisosOtorgables} y tiene sus tests; aca se mira que el actor se
 * arme por {@link AuthorizationGuard#actorSobre} y que cada rechazo salga con su status.
 */
@WebMvcTest(RolPermisosController.class)
@Import(ApiSliceSecurityConfig.class)
class RolPermisosControllerTest {

	private static final String RUTA = "/api/v1/organizations/1/roles/CONSULTORIO_ADMIN/permisos-otorgables";
	private static final OperatingActor ACTOR = new OperatingActor(7L, false, 3L);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MembershipService membershipService;

	@MockitoBean
	private AuthorizationGuard authorizationGuard;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@BeforeEach
	void sedeDelContexto() {
		given(authorizationGuard.actorSobre(anyLong(), anyBoolean(), any(), anyLong()))
				.willAnswer(inv -> new OperatingActor(inv.getArgument(0), inv.getArgument(1), 3L));
	}

	@Test
	@DisplayName("Devuelve el rol y sus permisos otorgables, con el actor del contexto")
	void devuelve_los_permisos_del_rol() throws Exception {
		given(membershipService.permisosOtorgables(ACTOR, 1L, "CONSULTORIO_ADMIN"))
				.willReturn(List.of("auditoria:read-clinica", "hc:read", "hc:write"));

		mockMvc.perform(get(RUTA).with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.roleCode").value("CONSULTORIO_ADMIN"))
				.andExpect(jsonPath("$.permissionCodes.length()").value(3))
				.andExpect(jsonPath("$.permissionCodes[2]").value("hc:write"));

		verify(authorizationGuard).actorSobre(eq(7L), eq(false), any(), eq(1L));
	}

	@Test
	@DisplayName("Un rol que no admite ninguno devuelve lista vacia, no 404")
	void rol_sin_otorgables_es_lista_vacia() throws Exception {
		given(membershipService.permisosOtorgables(any(), anyLong(), any())).willReturn(List.of());

		mockMvc.perform(get("/api/v1/organizations/1/roles/PACIENTE/permisos-otorgables")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.permissionCodes.length()").value(0));
	}

	@Test
	@DisplayName("Un rol invalido es 400 validation-error")
	void rol_invalido_es_400() throws Exception {
		given(membershipService.permisosOtorgables(any(), anyLong(), any()))
				.willThrow(new UnknownPermissionCodeException("PLATFORM_ADMIN", "no es de membership"));

		mockMvc.perform(get("/api/v1/organizations/1/roles/PLATFORM_ADMIN/permisos-otorgables")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"));
	}

	@Test
	@DisplayName("Otra organizacion que la del contexto es 404 y no llega al servicio")
	void otra_organizacion_es_404() throws Exception {
		given(authorizationGuard.actorSobre(anyLong(), anyBoolean(), any(), eq(2L)))
				.willThrow(new OrganizationNotFoundException(2L));

		mockMvc.perform(get("/api/v1/organizations/2/roles/PROFESIONAL/permisos-otorgables")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isNotFound());

		verifyNoInteractions(membershipService);
	}
}
