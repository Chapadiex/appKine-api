package com.akine.organization.api;

import com.akine.organization.application.AuditQueryService;
import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.OperatingActor;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.domain.exception.PermissionDeniedException;
import com.akine.platform.spi.audit.AuditEventSummary;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Contrato HTTP de la consulta de auditoria del tenant (RF-M24-002 a RF-M24-004).
 *
 * <p>La unica regla que vive en el controller es "exactamente un filtro": entidad, actor o
 * periodo. Lo que se verifica es que cada combinacion vaya al metodo correcto del servicio y
 * que las demas se rechacen con 400 antes de tocarlo. El tope de 90 dias y el recorte por sede
 * son del servicio y solo se comprueba aca que su rechazo salga como 400.
 */
@WebMvcTest(AuditEventController.class)
@Import(ApiSliceSecurityConfig.class)
class AuditEventControllerTest {

	private static final String BASE = "/api/v1/organizations/1/audit-events";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private AuditQueryService auditQueryService;

	@MockitoBean
	private AuthorizationGuard authorizationGuard;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("Por entidad: va a porEntidad, publica el hecho entero y ordena del mas nuevo al mas viejo")
	void filtro_por_entidad() throws Exception {
		given(authorizationGuard.actorSobre(anyLong(), anyBoolean(), any(), anyLong()))
				.willAnswer(inv -> new OperatingActor(inv.getArgument(0), inv.getArgument(1), 3L));
		given(auditQueryService.porEntidad(any(), eq(1L), eq("MEMBERSHIP"), eq(10L), any()))
				.willReturn(pagina(hecho()));

		mockMvc.perform(get(BASE).param("entityType", "MEMBERSHIP").param("entityId", "10")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(1))
				.andExpect(jsonPath("$.content[0].id").value(500))
				.andExpect(jsonPath("$.content[0].eventType").value("MEMBERSHIP_REVOKED"))
				.andExpect(jsonPath("$.content[0].entityType").value("MEMBERSHIP"))
				.andExpect(jsonPath("$.content[0].entityId").value(10))
				.andExpect(jsonPath("$.content[0].previousState").value("ACTIVA"))
				.andExpect(jsonPath("$.content[0].newState").value("REVOCADA"))
				.andExpect(jsonPath("$.content[0].details.roleCode").value("KINESIOLOGO"))
				.andExpect(jsonPath("$.content[0].reason").value("Renuncia"))
				.andExpect(jsonPath("$.content[0].occurredAt").exists())
				.andExpect(jsonPath("$.totalElements").value(1));

		ArgumentCaptor<OperatingActor> actor = ArgumentCaptor.forClass(OperatingActor.class);
		ArgumentCaptor<Pageable> paginado = ArgumentCaptor.forClass(Pageable.class);
		verify(auditQueryService).porEntidad(
				actor.capture(), eq(1L), eq("MEMBERSHIP"), eq(10L), paginado.capture());
		assertThat(actor.getValue()).isEqualTo(new OperatingActor(7L, false, 3L));
		assertThat(paginado.getValue().getSort())
				.isEqualTo(Sort.by(Sort.Direction.DESC, "occurredAt"));
	}

	@Test
	@DisplayName("Por actor: va a porActor con la cuenta pedida")
	void filtro_por_actor() throws Exception {
		given(auditQueryService.porActor(any(), anyLong(), anyLong(), any()))
				.willReturn(pagina());

		mockMvc.perform(get(BASE).param("actorAccountId", "42").with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(0));

		verify(auditQueryService).porActor(any(), eq(1L), eq(42L), any());
	}

	@Test
	@DisplayName("Por periodo: va a porPeriodo con los dos instantes parseados")
	void filtro_por_periodo() throws Exception {
		given(auditQueryService.porPeriodo(any(), anyLong(), any(), any(), any()))
				.willReturn(pagina());

		mockMvc.perform(get(BASE)
						.param("from", "2026-08-01T00:00:00Z")
						.param("to", "2026-08-23T00:00:00Z")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk());

		verify(auditQueryService).porPeriodo(any(), eq(1L),
				eq(Instant.parse("2026-08-01T00:00:00Z")),
				eq(Instant.parse("2026-08-23T00:00:00Z")), any());
	}

	@Test
	@DisplayName("Sin ningun filtro es 400 y no se consulta nada")
	void sin_filtro_es_400() throws Exception {
		mockMvc.perform(get(BASE).with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(
						"exactamente un filtro")));

		verifyNoInteractions(auditQueryService);
	}

	@Test
	@DisplayName("Combinar dos filtros es 400: el controller no elige uno por su cuenta")
	void dos_filtros_es_400() throws Exception {
		mockMvc.perform(get(BASE)
						.param("entityType", "MEMBERSHIP").param("entityId", "10")
						.param("actorAccountId", "42")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(auditQueryService);
	}

	@Test
	@DisplayName("Un filtro a medias —entityType sin entityId, from sin to— no cuenta como filtro: 400")
	void filtro_incompleto_es_400() throws Exception {
		mockMvc.perform(get(BASE).param("entityType", "MEMBERSHIP").with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest());
		mockMvc.perform(get(BASE).param("from", "2026-08-01T00:00:00Z")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(auditQueryService);
	}

	@Test
	@DisplayName("Un instante mal formado es 400 que nombra el parametro, no 500")
	void instante_mal_formado_es_400() throws Exception {
		mockMvc.perform(get(BASE).param("from", "ayer").param("to", "2026-08-23T00:00:00Z")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"))
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("from")));
	}

	@Test
	@DisplayName("Un rango que el servicio rechaza (mas de 90 dias o invertido) sale como 400")
	void rango_rechazado_por_el_servicio_es_400() throws Exception {
		given(auditQueryService.porPeriodo(any(), anyLong(), any(), any(), any()))
				.willThrow(new IllegalArgumentException("El rango no puede exceder los 90 dias"));

		mockMvc.perform(get(BASE)
						.param("from", "2026-01-01T00:00:00Z")
						.param("to", "2026-08-23T00:00:00Z")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.detail").value("El rango no puede exceder los 90 dias"));
	}

	@Test
	@DisplayName("Sin auditoria:read es 403 con el permiso faltante")
	void sin_permiso_es_403() throws Exception {
		given(auditQueryService.porActor(any(), anyLong(), anyLong(), any()))
				.willThrow(new PermissionDeniedException("auditoria:read", 7L, 1L));

		mockMvc.perform(get(BASE).param("actorAccountId", "42").with(ApiActors.miembro(7L)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.requiredPermission").value("auditoria:read"));
	}

	@Test
	@DisplayName("Organizacion de la ruta distinta de la del contexto: 404 antes de mirar los filtros")
	void organizacion_ajena_al_contexto_es_404() throws Exception {
		given(authorizationGuard.actorSobre(anyLong(), anyBoolean(), any(), eq(1L)))
				.willThrow(new OrganizationNotFoundException(1L));

		// Sin ningun filtro: si el controller validara antes, responderia 400 y confirmaria
		// que la ruta existe para esa organizacion.
		mockMvc.perform(get(BASE).with(ApiActors.miembro(7L)))
				.andExpect(status().isNotFound());

		verifyNoInteractions(auditQueryService);
	}

	@Test
	@DisplayName("Sin sesion autenticada es 403, jamas 401")
	void sin_sesion_es_403() throws Exception {
		mockMvc.perform(get(BASE).param("actorAccountId", "42"))
				.andExpect(status().isForbidden());

		verifyNoInteractions(auditQueryService);
	}

	@Test
	@DisplayName("El tamano de pagina se recorta al maximo en vez de rechazarse")
	void paginado_se_acota() throws Exception {
		given(auditQueryService.porActor(any(), anyLong(), anyLong(), any()))
				.willReturn(pagina());

		mockMvc.perform(get(BASE).param("actorAccountId", "42")
						.param("page", "-3").param("size", "10000")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk());

		ArgumentCaptor<Pageable> paginado = ArgumentCaptor.forClass(Pageable.class);
		verify(auditQueryService).porActor(any(), eq(1L), eq(42L), paginado.capture());
		assertThat(paginado.getValue().getPageNumber()).isZero();
		assertThat(paginado.getValue().getPageSize()).isEqualTo(ApiPaging.TAMANO_MAXIMO);
	}

	// =====================================================================================

	private static Page<AuditEventSummary> pagina(AuditEventSummary... hechos) {
		return new PageImpl<>(List.of(hechos), PageRequest.of(0, 20), hechos.length);
	}

	private static AuditEventSummary hecho() {
		return new AuditEventSummary(
				500L, 1L, 3L, 7L, "MEMBERSHIP_REVOKED", "MEMBERSHIP", 10L,
				"ACTIVA", "REVOCADA", Map.of("roleCode", "KINESIOLOGO"), "Renuncia",
				"corr-1", Instant.parse("2026-08-20T12:00:00Z"));
	}
}
