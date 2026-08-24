package com.akine.identity.api;

import com.akine.identity.application.DirectMembershipService;
import com.akine.identity.application.EmailSinCuentaException;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.tenant.TenantOperationalStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP del alta directa de colaboradores.
 *
 * <p>Lo que estos tests fijan es exactamente lo que un cambio distraido rompe sin que nada mas
 * se entere:
 *
 * <ul>
 *   <li>La organizacion sale del <b>contexto</b> y nunca del cuerpo. Aunque el JSON traiga un
 *       {@code organizationId}, el servicio recibe el del contexto: es la frontera cross-tenant
 *       de este endpoint.</li>
 *   <li>El email desconocido responde <b>404</b> —la decision del 24/08/2026— y el cuerpo no
 *       dice nada del email: si dijera "ese email no existe", el oraculo seria ademas
 *       explicito.</li>
 *   <li>Sin sesion es <b>403</b> y jamas 401: el interceptor del frontend borra el token ante
 *       cualquier 401 y entra en un bucle de login.</li>
 * </ul>
 *
 * <p>Que el intento fallido quede auditado NO se prueba aca sino en
 * {@code DirectMembershipServiceTest}: es una decision de {@code application} y verificarla
 * contra un servicio mockeado seria verificar el mock.
 */
@WebMvcTest(MembershipProvisioningController.class)
@Import(IdentityApiSliceSecurityConfig.class)
class MembershipProvisioningControllerTest {

	private static final long ORG_DEL_CONTEXTO = 10L;
	private static final long CONSULTORIO_DEL_CONTEXTO = 20L;
	private static final long ACTOR_ID = 99L;
	private static final long MEMBERSHIP_CREADA = 42L;

	/** Trae un organizationId que el controller tiene que ignorar por completo. */
	private static final String ALTA = """
			{"email":"Kine@Centro.Test","consultorioId":20,"roleCode":"KINESIOLOGO",\
			"reason":"Incorporacion del kinesiologo de la sede centro","organizationId":777}""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DirectMembershipService directMembershipService;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	private void conContexto() {
		given(tenantContextHolder.current()).willReturn(Optional.of(new RequestTenantContext(
				ACTOR_ID, ORG_DEL_CONTEXTO, CONSULTORIO_DEL_CONTEXTO, "ORG_ADMIN",
				TenantOperationalStatus.ACTIVA)));
	}

	@Test
	@DisplayName("201 con Location al recurso de organization, y el tenant sale del contexto")
	void alta_exitosa() throws Exception {
		conContexto();
		given(directMembershipService.vincular(any(), anyString(), any(), anyString(), anyString()))
				.willReturn(MEMBERSHIP_CREADA);

		mockMvc.perform(post("/api/v1/memberships")
						.with(IdentityApiActors.miembro(ACTOR_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(ALTA))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location",
						"/api/v1/organizations/10/memberships/42"))
				.andExpect(jsonPath("$.membershipId").value(42));

		ArgumentCaptor<DirectMembershipService.Actor> actor =
				ArgumentCaptor.forClass(DirectMembershipService.Actor.class);
		verify(directMembershipService).vincular(
				actor.capture(), eq("Kine@Centro.Test"), eq(CONSULTORIO_DEL_CONTEXTO),
				eq("KINESIOLOGO"), eq("Incorporacion del kinesiologo de la sede centro"));

		// 777 viajaba en el cuerpo. Si alguna vez aparece aca, el endpoint dejo de ser
		// multi-tenant seguro.
		assertThat(actor.getValue().organizationId()).isEqualTo(ORG_DEL_CONTEXTO);
		assertThat(actor.getValue().accountId()).isEqualTo(ACTOR_ID);
		assertThat(actor.getValue().platformAdmin()).isFalse();
	}

	@Test
	@DisplayName("404 cuando el email no tiene cuenta, sin decir que fue el email")
	void email_sin_cuenta() throws Exception {
		conContexto();
		willThrow(new EmailSinCuentaException("kine@centro.test"))
				.given(directMembershipService)
				.vincular(any(), anyString(), any(), anyString(), anyString());

		mockMvc.perform(post("/api/v1/memberships")
						.with(IdentityApiActors.miembro(ACTOR_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content(ALTA))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"))
				.andExpect(jsonPath("$.detail")
						.value("El recurso solicitado no existe o no esta disponible."));
	}

	@Test
	@DisplayName("sin sesion autenticada es 403 y jamas 401")
	void sin_sesion() throws Exception {
		mockMvc.perform(post("/api/v1/memberships")
						.contentType(MediaType.APPLICATION_JSON)
						.content(ALTA))
				.andExpect(status().isForbidden());

		verify(directMembershipService, never())
				.vincular(any(), anyString(), any(), anyString(), anyString());
	}

	@Test
	@DisplayName("400 sin motivo: la auditoria no puede quedar sin el por que")
	void sin_motivo() throws Exception {
		conContexto();

		mockMvc.perform(post("/api/v1/memberships")
						.with(IdentityApiActors.miembro(ACTOR_ID))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"kine@centro.test","roleCode":"KINESIOLOGO"}"""))
				.andExpect(status().isBadRequest());

		verify(directMembershipService, never())
				.vincular(any(), anyString(), any(), anyString(), anyString());
	}
}
