package com.akine.identity.api;

import com.akine.identity.application.AccountAdminService;
import com.akine.identity.application.AccountView;
import com.akine.identity.domain.EstadoCuenta;
import com.akine.identity.domain.exception.AccountNotFoundException;
import com.akine.identity.domain.exception.InvalidAccountTransitionException;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.tenant.TenantOperationalStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.Optional;

import static com.akine.identity.IdentityFixtures.CUENTA_ID;


import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de la administracion de cuentas (RF-M02-005, ADR-0019).
 *
 * <p>Los dos invariantes que estos tests protegen: que la organizacion del actor salga del
 * contexto revalidado y no de un parametro, y que una cuenta ajena responda <b>404</b> —nunca
 * 403 y nunca 200—.
 */
@WebMvcTest(AccountAdminController.class)
@Import(IdentityApiSliceSecurityConfig.class)
class AccountAdminControllerTest {

	private static final long ORG_DEL_ACTOR = 10L;
	private static final long ACTOR_ID = 99L;
	private static final Instant BLOQUEADA_EN = Instant.parse("2026-08-23T14:05:00Z");

	private static final String MOTIVO = """
			{"reason":"Baja del profesional por fin de contrato"}""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AccountAdminService accountAdminService;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	private void conContexto() {
		given(tenantContextHolder.current()).willReturn(Optional.of(new RequestTenantContext(
				ACTOR_ID, ORG_DEL_ACTOR, 20L, "ORG_ADMIN", TenantOperationalStatus.ACTIVA)));
	}

	/**
	 * Vistas sinteticas de {@code application}.
	 *
	 * <p>Se construyen a mano y no desde {@code IdentityFixtures}: lo que el controller recibe
	 * ya es una {@link AccountView}, no la entity, y armarla desde la entity solo para
	 * convertirla otra vez ocultaria justamente lo que estos tests verifican —que lo que sale
	 * por HTTP viene de la vista y no del modelo persistido—.
	 */
	private static AccountView bloqueada() {
		return new AccountView(CUENTA_ID, "ana.gomez@ejemplo.test", "Ana", "Gomez",
				EstadoCuenta.BLOQUEADA.name(), BLOQUEADA_EN, BLOQUEADA_EN);
	}

	private static AccountView activa() {
		return new AccountView(CUENTA_ID, "ana.gomez@ejemplo.test", "Ana", "Gomez",
				EstadoCuenta.ACTIVA.name(), null, BLOQUEADA_EN);
	}

	@Test
	@DisplayName("bloquear devuelve la cuenta con su estado nuevo y sin datos sensibles")
	void bloquear_devuelve_la_cuenta_sin_datos_sensibles() throws Exception {
		conContexto();
		given(accountAdminService.bloquear(any(), eq(CUENTA_ID), anyString()))
				.willReturn(bloqueada());

		MvcResult resultado = mockMvc.perform(post("/api/v1/accounts/" + CUENTA_ID + "/block")
						.contentType(MediaType.APPLICATION_JSON)
						.content(MOTIVO)
						.with(IdentityApiActors.miembro(ACTOR_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(CUENTA_ID))
				.andExpect(jsonPath("$.status").value("BLOQUEADA"))
				.andExpect(jsonPath("$.blockedAt").isNotEmpty())
				.andReturn();

		String cuerpo = resultado.getResponse().getContentAsString();
		assertThat(cuerpo)
				.as("ni el hash, ni los intentos fallidos, ni el ultimo login pueden salir")
				.doesNotContain("passwordHash").doesNotContain("hash")
				.doesNotContain("intentos").doesNotContain("ultimoLogin");
	}

	@Test
	@DisplayName("la organizacion del actor sale del contexto revalidado, no de un parametro")
	void la_organizacion_sale_del_contexto_revalidado() throws Exception {
		conContexto();
		given(accountAdminService.bloquear(any(), anyLong(), anyString())).willReturn(bloqueada());

		mockMvc.perform(post("/api/v1/accounts/" + CUENTA_ID + "/block")
						.param("organizationId", "777")
						.contentType(MediaType.APPLICATION_JSON)
						.content(MOTIVO)
						.with(IdentityApiActors.miembro(ACTOR_ID)))
				.andExpect(status().isOk());

		verify(accountAdminService).bloquear(
				eq(new AccountAdminService.Actor(ACTOR_ID, ORG_DEL_ACTOR, false)),
				eq(CUENTA_ID),
				anyString());
	}

	@Test
	@DisplayName("un platform admin opera sin contexto de organizacion")
	void un_platform_admin_opera_sin_contexto() throws Exception {
		given(tenantContextHolder.current()).willReturn(Optional.empty());
		given(accountAdminService.desactivar(any(), anyLong(), anyString()))
				.willReturn(activa());

		mockMvc.perform(post("/api/v1/accounts/" + CUENTA_ID + "/deactivate")
						.contentType(MediaType.APPLICATION_JSON)
						.content(MOTIVO)
						.with(IdentityApiActors.platformAdmin(ACTOR_ID)))
				.andExpect(status().isOk());

		verify(accountAdminService).desactivar(
				eq(new AccountAdminService.Actor(ACTOR_ID, null, true)), eq(CUENTA_ID), anyString());
	}

	@Test
	@DisplayName("una cuenta de otra organizacion responde 404, nunca 403 y nunca 200")
	void una_cuenta_ajena_es_404() throws Exception {
		conContexto();
		willThrow(new AccountNotFoundException(CUENTA_ID))
				.given(accountAdminService).bloquear(any(), anyLong(), anyString());

		MvcResult resultado = mockMvc.perform(post("/api/v1/accounts/" + CUENTA_ID + "/block")
						.contentType(MediaType.APPLICATION_JSON)
						.content(MOTIVO)
						.with(IdentityApiActors.miembro(ACTOR_ID)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"))
				.andReturn();

		// El id aparece en "instance" porque instance ES la ruta que el cliente acaba de pedir:
		// no es informacion nueva. Lo que no puede pasar es que el motivo real se filtre en el
		// texto, que es lo que distinguiria "no existe" de "es de otra organizacion".
		assertThat(resultado.getResponse().getContentAsString())
				.contains("\"detail\":\"El recurso solicitado no existe o no esta disponible.\"")
				.doesNotContain("organizacion")
				.doesNotContain("membership");
	}

	@Test
	@DisplayName("un actor que no administra su organizacion recibe 403, no 404")
	void un_actor_sin_permiso_es_403() throws Exception {
		conContexto();
		willThrow(new AccessDeniedException("Se requiere administrar la organizacion"))
				.given(accountAdminService).bloquear(any(), anyLong(), anyString());

		mockMvc.perform(post("/api/v1/accounts/" + CUENTA_ID + "/block")
						.contentType(MediaType.APPLICATION_JSON)
						.content(MOTIVO)
						.with(IdentityApiActors.miembro(ACTOR_ID)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"));
	}

	@Test
	@DisplayName("sin sesion autenticada es 403 y jamas 401")
	void sin_sesion_es_403_nunca_401() throws Exception {
		mockMvc.perform(post("/api/v1/accounts/" + CUENTA_ID + "/block")
						.contentType(MediaType.APPLICATION_JSON)
						.content(MOTIVO))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"));

		verify(accountAdminService, never()).bloquear(any(), anyLong(), anyString());
	}

	@Test
	@DisplayName("una transicion imposible responde 409 con los dos estados")
	void una_transicion_imposible_es_409() throws Exception {
		conContexto();
		willThrow(new InvalidAccountTransitionException(
				EstadoCuenta.BLOQUEADA, EstadoCuenta.BLOQUEADA))
				.given(accountAdminService).bloquear(any(), anyLong(), anyString());

		mockMvc.perform(post("/api/v1/accounts/" + CUENTA_ID + "/block")
						.contentType(MediaType.APPLICATION_JSON)
						.content(MOTIVO)
						.with(IdentityApiActors.miembro(ACTOR_ID)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/conflict"))
				.andExpect(jsonPath("$.fromStatus").value("BLOQUEADA"))
				.andExpect(jsonPath("$.toStatus").value("BLOQUEADA"));
	}

	@Test
	@DisplayName("sin motivo la transicion es 400 y no llega al servicio")
	void sin_motivo_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/accounts/" + CUENTA_ID + "/block")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"reason":"  "}""")
						.with(IdentityApiActors.miembro(ACTOR_ID)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"));

		verify(accountAdminService, never()).bloquear(any(), anyLong(), anyString());
	}

	@Test
	@DisplayName("desbloquear y desactivar recorren el mismo camino de actor y motivo")
	void desbloquear_y_desactivar_usan_el_mismo_camino() throws Exception {
		conContexto();
		given(accountAdminService.desbloquear(any(), anyLong(), anyString()))
				.willReturn(activa());
		given(accountAdminService.desactivar(any(), anyLong(), anyString()))
				.willReturn(activa());

		mockMvc.perform(post("/api/v1/accounts/" + CUENTA_ID + "/unblock")
						.contentType(MediaType.APPLICATION_JSON)
						.content(MOTIVO)
						.with(IdentityApiActors.miembro(ACTOR_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("ACTIVA"));

		mockMvc.perform(post("/api/v1/accounts/" + CUENTA_ID + "/deactivate")
						.contentType(MediaType.APPLICATION_JSON)
						.content(MOTIVO)
						.with(IdentityApiActors.miembro(ACTOR_ID)))
				.andExpect(status().isOk());

		AccountAdminService.Actor esperado =
				new AccountAdminService.Actor(ACTOR_ID, ORG_DEL_ACTOR, false);
		verify(accountAdminService).desbloquear(
				eq(esperado), eq(CUENTA_ID), eq("Baja del profesional por fin de contrato"));
		verify(accountAdminService).desactivar(
				eq(esperado), eq(CUENTA_ID), eq("Baja del profesional por fin de contrato"));
	}

	@Test
	@DisplayName("la respuesta publica el momento de bloqueo, que es lo que la operacion cambio")
	void la_respuesta_publica_el_momento_de_bloqueo() throws Exception {
		conContexto();
		AccountView cuenta = bloqueada();
		given(accountAdminService.bloquear(any(), anyLong(), anyString())).willReturn(cuenta);

		mockMvc.perform(post("/api/v1/accounts/" + CUENTA_ID + "/block")
						.contentType(MediaType.APPLICATION_JSON)
						.content(MOTIVO)
						.with(IdentityApiActors.miembro(ACTOR_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value(cuenta.email()))
				.andExpect(jsonPath("$.firstName").value(cuenta.nombre()))
				.andExpect(jsonPath("$.lastName").value(cuenta.apellido()));

		assertThat(cuenta.bloqueadaEn()).isBefore(Instant.now().plusSeconds(1));
	}
}
