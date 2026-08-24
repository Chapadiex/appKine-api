package com.akine.organization.api;

import com.akine.organization.application.LimitUsageView;
import com.akine.organization.application.PlanChangeOutcome;
import com.akine.organization.application.PlanNotFoundException;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.SubscriptionService;
import com.akine.organization.application.SubscriptionTransitionView;
import com.akine.organization.application.SubscriptionView;
import com.akine.organization.domain.SubscriptionStatus;
import com.akine.organization.domain.exception.InvalidSubscriptionTransitionException;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.SubscriptionSuspendedException;
import com.akine.organization.spi.LimitCode;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de la suscripcion del tenant (RF-M01-006, RF-M01-007, RN-M01-004).
 *
 * <p>Las transiciones son un POST a una subcoleccion porque son hechos que se agregan a un
 * historico append-only, no la edicion de un campo. Lo que se verifica aca es esa forma y el
 * mapeo de cada rechazo; la maquina de estados tiene sus propios tests en el dominio.
 */
@WebMvcTest(SubscriptionController.class)
@Import(ApiSliceSecurityConfig.class)
class SubscriptionControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SubscriptionService subscriptionService;

	@MockitoBean
	private AuthorizationGuard authorizationGuard;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	// =====================================================================================
	// GET /api/v1/organizations/{orgId}/subscription
	// =====================================================================================

	@Test
	@DisplayName("La suscripcion publica plan, estado, consumo de limites y transiciones posibles")
	void devuelve_la_suscripcion_con_limites_y_transiciones() throws Exception {
		given(subscriptionService.find(1L)).willReturn(vista(SubscriptionStatus.ACTIVA, 2L));

		mockMvc.perform(get("/api/v1/organizations/1/subscription").with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(50))
				.andExpect(jsonPath("$.organizationId").value(1))
				.andExpect(jsonPath("$.planCode").value("BASICO"))
				.andExpect(jsonPath("$.planName").value("Basico"))
				.andExpect(jsonPath("$.status").value("ACTIVA"))
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.startedAt").exists())
				.andExpect(jsonPath("$.limits[0].code").value("MAX_CONSULTORIOS"))
				.andExpect(jsonPath("$.limits[0].limitValue").value(3))
				.andExpect(jsonPath("$.limits[0].currentUsage").value(2))
				.andExpect(jsonPath("$.limits[0].exceeded").value(false))
				.andExpect(jsonPath("$.allowedTargets").isArray());

		verify(authorizationGuard).requireOrgAdmin(7L, 1L, null, false);
	}

	@Test
	@DisplayName("Un limite ya superado viaja con exceeded=true: un downgrade avisa, no borra")
	void un_limite_superado_viaja_como_excedido() throws Exception {
		given(subscriptionService.find(1L)).willReturn(new SubscriptionView(
				50L, 1L, "BASICO", "Basico", SubscriptionStatus.ACTIVA,
				Instant.parse("2026-01-15T13:45:00Z"), 0L,
				List.of(new LimitUsageView(LimitCode.MAX_CONSULTORIOS, 1, 4)),
				Set.of()));

		mockMvc.perform(get("/api/v1/organizations/1/subscription").with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.limits[0].exceeded").value(true))
				.andExpect(jsonPath("$.allowedTargets.length()").value(0));
	}

	@Test
	@DisplayName("Estar en el tenant sin administrarlo es 403 al leer la suscripcion")
	void lectura_sin_rol_de_administrador_es_403() throws Exception {
		willThrow(new AccessDeniedException("Se requiere administrar la organizacion"))
				.given(authorizationGuard).requireOrgAdmin(eq(7L), eq(1L), any(), anyBoolean());

		mockMvc.perform(get("/api/v1/organizations/1/subscription").with(ApiActors.miembro(7L)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"));

		verify(subscriptionService, never()).find(anyLong());
	}

	@Test
	@DisplayName("Sin sesion autenticada la lectura de la suscripcion es 403 y jamas 401")
	void lectura_sin_sesion_es_403_nunca_401() throws Exception {
		mockMvc.perform(get("/api/v1/organizations/1/subscription"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value(403));
	}

	@Test
	@DisplayName("Una organizacion inexistente o ajena responde 404 tambien en la suscripcion")
	void lectura_de_organizacion_no_accesible_es_404() throws Exception {
		given(subscriptionService.find(99L)).willThrow(new OrganizationNotFoundException(99L));

		mockMvc.perform(get("/api/v1/organizations/99/subscription")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"));
	}

	// =====================================================================================
	// POST /api/v1/organizations/{orgId}/subscription/transitions
	// =====================================================================================

	@Test
	@DisplayName("La transicion aplicada devuelve la suscripcion en su estado nuevo")
	void transicion_aplicada_devuelve_el_estado_nuevo() throws Exception {
		given(subscriptionService.transition(
				eq(1L), eq(SubscriptionStatus.SUSPENDIDA), eq(SubscriptionStatus.ACTIVA),
				any(), any()))
				.willReturn(vista(SubscriptionStatus.SUSPENDIDA, 3L));

		mockMvc.perform(post("/api/v1/organizations/1/subscription/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"toStatus":"SUSPENDIDA",
								 "expectedStatus":"ACTIVA",
								 "reason":"Falta de pago de tres periodos consecutivos"}""")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("SUSPENDIDA"))
				.andExpect(jsonPath("$.version").value(3));

		verify(subscriptionService).transition(
				1L,
				SubscriptionStatus.SUSPENDIDA,
				SubscriptionStatus.ACTIVA,
				"Falta de pago de tres periodos consecutivos",
				1L);
	}

	@Test
	@DisplayName("La transicion esta reservada a la administracion de plataforma: sin el flag es 403")
	void transicion_sin_platform_admin_es_403() throws Exception {
		willThrow(new AccessDeniedException("Operacion reservada a la administracion de plataforma"))
				.given(authorizationGuard).requirePlatformAdmin(false);

		mockMvc.perform(post("/api/v1/organizations/1/subscription/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"toStatus\":\"CANCELADA\",\"reason\":\"Cierre del centro\"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isForbidden());

		verify(subscriptionService, never()).transition(anyLong(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("Sin estado destino la transicion es 400 con el campo senalado")
	void transicion_sin_estado_destino_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/organizations/1/subscription/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Cierre del centro\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.toStatus").exists());
	}

	@Test
	@DisplayName("Un motivo mas largo que el maximo del contrato se rechaza con 400")
	void transicion_con_motivo_demasiado_largo_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/organizations/1/subscription/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"toStatus\":\"SUSPENDIDA\",\"reason\":\"" + "x".repeat(501) + "\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.reason").exists());
	}

	@Test
	@DisplayName("Falta el motivo donde es obligatorio: 400 con el mensaje que el dominio redacto")
	void transicion_sin_motivo_obligatorio_es_400() throws Exception {
		given(subscriptionService.transition(anyLong(), any(), any(), any(), any()))
				.willThrow(new IllegalArgumentException(
						"El motivo es obligatorio para suspender la suscripcion"));

		mockMvc.perform(post("/api/v1/organizations/1/subscription/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"toStatus\":\"SUSPENDIDA\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(
						"El motivo es obligatorio para suspender la suscripcion"));
	}

	@Test
	@DisplayName("Un salto que la maquina no admite es 409 y publica los estados origen y destino")
	void transicion_no_admitida_es_409_con_los_estados() throws Exception {
		given(subscriptionService.transition(anyLong(), any(), any(), any(), any()))
				.willThrow(new InvalidSubscriptionTransitionException(
						SubscriptionStatus.CANCELADA, SubscriptionStatus.ACTIVA));

		mockMvc.perform(post("/api/v1/organizations/1/subscription/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"toStatus\":\"ACTIVA\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(
						"https://akine.app/problems/invalid-subscription-transition"))
				.andExpect(jsonPath("$.title").value("Transicion de suscripcion no permitida"))
				.andExpect(jsonPath("$.fromStatus").value("CANCELADA"))
				.andExpect(jsonPath("$.toStatus").value("ACTIVA"));
	}

	@Test
	@DisplayName("Un expectedStatus que ya no coincide con el real es 409 de concurrencia")
	void transicion_con_expected_status_desactualizado_es_409() throws Exception {
		given(subscriptionService.transition(anyLong(), any(), any(), any(), any()))
				.willThrow(new OptimisticLockingFailureException(
						"El estado de la suscripcion cambio: revalidar antes de reintentar"));

		mockMvc.perform(post("/api/v1/organizations/1/subscription/transitions")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"toStatus\":\"CANCELADA\",\"expectedStatus\":\"ACTIVA\","
								+ "\"reason\":\"Cierre\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/conflict"));
	}

	// =====================================================================================
	// GET /api/v1/organizations/{orgId}/subscription/transitions
	// =====================================================================================

	@Test
	@DisplayName("El historico devuelve la pagina pedida, con la fila de alta sin estado previo")
	void historico_devuelve_la_pagina() throws Exception {
		Page<SubscriptionTransitionView> pagina = new PageImpl<>(
				List.of(
						new SubscriptionTransitionView(
								2L, SubscriptionStatus.ACTIVA, SubscriptionStatus.SUSPENDIDA,
								"BASICO", "BASICO", "Falta de pago", 1L,
								Instant.parse("2026-03-01T10:00:00Z")),
						new SubscriptionTransitionView(
								1L, null, SubscriptionStatus.ACTIVA,
								null, "BASICO", null, null,
								Instant.parse("2026-01-15T13:45:00Z"))),
				PageRequest.of(0, 20),
				2);
		given(subscriptionService.history(eq(1L), any(Pageable.class))).willReturn(pagina);

		mockMvc.perform(get("/api/v1/organizations/1/subscription/transitions")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].id").value(2))
				.andExpect(jsonPath("$.content[0].fromStatus").value("ACTIVA"))
				.andExpect(jsonPath("$.content[0].toStatus").value("SUSPENDIDA"))
				.andExpect(jsonPath("$.content[0].fromPlanCode").value("BASICO"))
				.andExpect(jsonPath("$.content[0].reason").value("Falta de pago"))
				.andExpect(jsonPath("$.content[0].actorAccountId").value(1))
				.andExpect(jsonPath("$.content[1].fromStatus").doesNotExist())
				.andExpect(jsonPath("$.content[1].actorAccountId").doesNotExist())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.totalPages").value(1));

		verify(authorizationGuard).requireOrgAdmin(7L, 1L, null, false);
	}

	@Test
	@DisplayName("El paginado del historico se normaliza antes de llegar al repositorio")
	void el_paginado_del_historico_se_acota() throws Exception {
		given(subscriptionService.history(eq(1L), any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

		mockMvc.perform(get("/api/v1/organizations/1/subscription/transitions")
						.param("page", "-5")
						.param("size", "9999")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk());

		ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
		verify(subscriptionService).history(eq(1L), captor.capture());
		assertThat(captor.getValue().getPageNumber()).isZero();
		assertThat(captor.getValue().getPageSize()).isEqualTo(ApiPaging.TAMANO_MAXIMO);
	}

	@Test
	@DisplayName("El historico de un tenant que no se administra es 403")
	void historico_sin_rol_de_administrador_es_403() throws Exception {
		willThrow(new AccessDeniedException("Se requiere administrar la organizacion"))
				.given(authorizationGuard).requireOrgAdmin(eq(7L), eq(1L), any(), anyBoolean());

		mockMvc.perform(get("/api/v1/organizations/1/subscription/transitions")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isForbidden());

		verify(subscriptionService, never()).history(anyLong(), any());
	}

	// =====================================================================================
	// POST /api/v1/organizations/{orgId}/subscription/plan-changes
	// =====================================================================================

	@Test
	@DisplayName("El cambio de plan devuelve la suscripcion nueva y los limites que ya se exceden")
	void cambio_de_plan_devuelve_warnings_sin_rechazar() throws Exception {
		given(subscriptionService.changePlan(eq(1L), eq("PROFESIONAL"), eq(2L), any()))
				.willReturn(new PlanChangeOutcome(
						vista(SubscriptionStatus.ACTIVA, 3L),
						List.of(new LimitUsageView(LimitCode.MAX_CONSULTORIOS, 1, 4)),
						true));

		mockMvc.perform(post("/api/v1/organizations/1/subscription/plan-changes")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"planCode\":\"PROFESIONAL\",\"expectedVersion\":2}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.applied").value(true))
				.andExpect(jsonPath("$.subscription.version").value(3))
				.andExpect(jsonPath("$.warnings.length()").value(1))
				.andExpect(jsonPath("$.warnings[0].code").value("MAX_CONSULTORIOS"))
				.andExpect(jsonPath("$.warnings[0].exceeded").value(true));

		verify(subscriptionService).changePlan(1L, "PROFESIONAL", 2L, 1L);
	}

	@Test
	@DisplayName("Reintentar un cambio ya aplicado responde applied=false, no un segundo efecto")
	void cambio_de_plan_repetido_responde_no_aplicado() throws Exception {
		given(subscriptionService.changePlan(anyLong(), any(), anyLong(), any()))
				.willReturn(new PlanChangeOutcome(
						vista(SubscriptionStatus.ACTIVA, 2L), List.of(), false));

		mockMvc.perform(post("/api/v1/organizations/1/subscription/plan-changes")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"planCode\":\"BASICO\",\"expectedVersion\":2}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.applied").value(false))
				.andExpect(jsonPath("$.warnings.length()").value(0));
	}

	@Test
	@DisplayName("Sin codigo de plan ni version esperada el cambio es 400 con los dos campos")
	void cambio_de_plan_sin_campos_obligatorios_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/organizations/1/subscription/plan-changes")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.planCode").exists())
				.andExpect(jsonPath("$.errors.expectedVersion").exists());
	}

	@Test
	@DisplayName("El cambio de plan esta reservado a la administracion de plataforma")
	void cambio_de_plan_sin_platform_admin_es_403() throws Exception {
		willThrow(new AccessDeniedException("Operacion reservada a la administracion de plataforma"))
				.given(authorizationGuard).requirePlatformAdmin(false);

		mockMvc.perform(post("/api/v1/organizations/1/subscription/plan-changes")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"planCode\":\"PROFESIONAL\",\"expectedVersion\":0}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isForbidden());

		verify(subscriptionService, never()).changePlan(anyLong(), any(), anyLong(), any());
	}

	@Test
	@DisplayName("Con la suscripcion no activa el cambio de plan es 409 subscription-suspended")
	void cambio_de_plan_con_suscripcion_suspendida_es_409() throws Exception {
		given(subscriptionService.changePlan(anyLong(), any(), anyLong(), any()))
				.willThrow(new SubscriptionSuspendedException(1L));

		mockMvc.perform(post("/api/v1/organizations/1/subscription/plan-changes")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"planCode\":\"PROFESIONAL\",\"expectedVersion\":0}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(
						"https://akine.app/problems/subscription-suspended"))
				.andExpect(jsonPath("$.title").value("Suscripcion suspendida"));
	}

	@Test
	@DisplayName("Una version de suscripcion vieja responde 409 de concurrencia")
	void cambio_de_plan_con_version_vieja_es_409() throws Exception {
		given(subscriptionService.changePlan(anyLong(), any(), anyLong(), any()))
				.willThrow(new OptimisticLockingFailureException(
						"La suscripcion fue modificada por otra operacion"));

		mockMvc.perform(post("/api/v1/organizations/1/subscription/plan-changes")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"planCode\":\"PROFESIONAL\",\"expectedVersion\":0}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/conflict"));
	}

	@Test
	@DisplayName("Un plan no contratable responde 404 sin nombrar el codigo pedido")
	void cambio_a_plan_no_contratable_es_404() throws Exception {
		given(subscriptionService.changePlan(anyLong(), any(), anyLong(), any()))
				.willThrow(new PlanNotFoundException("RETIRADO"));

		mockMvc.perform(post("/api/v1/organizations/1/subscription/plan-changes")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"planCode\":\"RETIRADO\",\"expectedVersion\":0}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"));
	}

	@Test
	@DisplayName("Una version esperada negativa se rechaza con 400 antes de tocar la base")
	void cambio_de_plan_con_version_negativa_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/organizations/1/subscription/plan-changes")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"planCode\":\"PROFESIONAL\",\"expectedVersion\":-1}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.expectedVersion").exists());
	}

	private static SubscriptionView vista(SubscriptionStatus status, long version) {
		return new SubscriptionView(
				50L,
				1L,
				"BASICO",
				"Basico",
				status,
				Instant.parse("2026-01-15T13:45:00Z"),
				version,
				List.of(new LimitUsageView(LimitCode.MAX_CONSULTORIOS, 3, 2)),
				Set.of(SubscriptionStatus.SUSPENDIDA, SubscriptionStatus.CANCELADA));
	}
}
