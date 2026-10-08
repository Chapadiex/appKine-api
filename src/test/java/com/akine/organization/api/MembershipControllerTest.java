package com.akine.organization.api;

import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.MembershipGrantView;
import com.akine.organization.application.MembershipService;
import com.akine.organization.application.MembershipView;
import com.akine.organization.application.OperatingActor;
import com.akine.organization.domain.MembershipEstado;
import com.akine.organization.domain.exception.FounderRevocationNotAllowedException;
import com.akine.organization.domain.exception.GrantAlreadyActiveException;
import com.akine.organization.domain.exception.LastAdminException;
import com.akine.organization.domain.exception.MembershipNotAccessibleException;
import com.akine.organization.domain.exception.MembershipNotActiveException;
import com.akine.organization.domain.exception.PermissionDeniedException;
import com.akine.organization.domain.exception.SelfRevokeNotAllowedException;
import com.akine.organization.domain.exception.UnknownPermissionCodeException;
import com.akine.organization.spi.ColaboradorDesvinculacionProbe;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de los vinculos de colaboradores (M05).
 *
 * <p>Las reglas —ultimo administrador, self-revoke, fundador, catalogo otorgable— viven en
 * {@code MembershipService} y tienen sus propios tests. Lo que se verifica aca es lo que sólo
 * la capa web puede romper: que cada parametro llegue al servicio, que el actor se arme con la
 * sede del contexto, y que cada rechazo salga con el status y el {@code type} que el frontend
 * usa para ramificar.
 */
@WebMvcTest(MembershipController.class)
@Import(ApiSliceSecurityConfig.class)
class MembershipControllerTest {

	private static final String BASE = "/api/v1/organizations/1/memberships";

	private static final OperatingActor ACTOR = new OperatingActor(7L, false, 3L);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private MembershipService membershipService;

	@MockitoBean
	private AuthorizationGuard authorizationGuard;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	/** Un mock devuelve 0 para un {@code Long}: la sede del contexto se fija explicita. */
	@BeforeEach
	void sedeDelContexto() {
		given(authorizationGuard.actorSobre(anyLong(), anyBoolean(), any(), anyLong()))
				.willAnswer(inv -> new OperatingActor(inv.getArgument(0), inv.getArgument(1), 3L));
	}

	// =====================================================================================
	// Lecturas
	// =====================================================================================

	@Test
	@DisplayName("El listado incluye a los revocados, ordena por id y arma el actor con la sede del contexto")
	void listado_devuelve_la_pagina_y_arma_el_actor() throws Exception {
		given(membershipService.list(any(), eq(1L), any(Pageable.class)))
				.willReturn(new PageImpl<>(
						List.of(vinculo(10L, "ACTIVA"), vinculo(11L, "REVOCADA")),
						PageRequest.of(0, 20), 2));

		mockMvc.perform(get(BASE).with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].id").value(10))
				.andExpect(jsonPath("$.content[0].accountName").value("Ana Perez"))
				.andExpect(jsonPath("$.content[0].roleCode").value("KINESIOLOGO"))
				.andExpect(jsonPath("$.content[1].estado").value("REVOCADA"))
				.andExpect(jsonPath("$.content[1].revokedReason").value("Renuncia"))
				.andExpect(jsonPath("$.totalElements").value(2));

		ArgumentCaptor<OperatingActor> actor = ArgumentCaptor.forClass(OperatingActor.class);
		ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
		verify(membershipService).list(actor.capture(), eq(1L), pagina.capture());
		assertThat(actor.getValue()).isEqualTo(ACTOR);
		assertThat(pagina.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
	}

	@Test
	@DisplayName("El paginado del listado se normaliza: pagina negativa a cero y tamano al maximo")
	void listado_acota_el_paginado() throws Exception {
		given(membershipService.list(any(), anyLong(), any(Pageable.class)))
				.willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

		mockMvc.perform(get(BASE).param("page", "-1").param("size", "500")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk());

		ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
		verify(membershipService).list(any(), eq(1L), pagina.capture());
		assertThat(pagina.getValue().getPageNumber()).isZero();
		assertThat(pagina.getValue().getPageSize()).isEqualTo(ApiPaging.TAMANO_MAXIMO);
	}

	@Test
	@DisplayName("Sin sesion autenticada el listado es 403, jamas 401, y no llega al servicio")
	void listado_sin_sesion_es_403() throws Exception {
		mockMvc.perform(get(BASE))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"));

		verifyNoInteractions(membershipService);
	}

	@Test
	@DisplayName("Sin colaborador:read es 403 y publica el permiso que falta")
	void listado_sin_permiso_publica_el_permiso_faltante() throws Exception {
		given(membershipService.list(any(), anyLong(), any(Pageable.class)))
				.willThrow(new PermissionDeniedException("colaborador:read", 7L, 1L));

		mockMvc.perform(get(BASE).with(ApiActors.miembro(7L)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"))
				.andExpect(jsonPath("$.requiredPermission").value("colaborador:read"));
	}

	@Test
	@DisplayName("Un vinculo inexistente o de otro tenant responde 404")
	void detalle_de_vinculo_no_accesible_es_404() throws Exception {
		given(membershipService.find(any(), eq(1L), eq(99L)))
				.willThrow(new MembershipNotAccessibleException(99L));

		mockMvc.perform(get(BASE + "/99").with(ApiActors.miembro(7L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"));
	}

	@Test
	@DisplayName("Los permisos adicionales vigentes se devuelven como lista plana")
	void permisos_adicionales_se_listan() throws Exception {
		given(membershipService.grants(any(), eq(1L), eq(10L)))
				.willReturn(List.of(permiso("auditoria:read")));

		mockMvc.perform(get(BASE + "/10/grants").with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].permissionCode").value("auditoria:read"))
				.andExpect(jsonPath("$[0].membershipId").value(10))
				.andExpect(jsonPath("$[0].grantedByAccountId").value(7));
	}

	@Test
	@DisplayName("El impacto de desvincular informa lo pendiente sin bloquear nada")
	void impacto_de_desvinculacion() throws Exception {
		given(membershipService.desvinculacionImpacto(any(), eq(1L), eq(10L)))
				.willReturn(new ColaboradorDesvinculacionProbe.Impacto("turnos", 0, null));

		mockMvc.perform(get(BASE + "/10/desvinculacion-impacto").with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tipo").value("turnos"))
				.andExpect(jsonPath("$.count").value(0));
	}

	// =====================================================================================
	// Cambio de rol y de alcance
	// =====================================================================================

	@Test
	@DisplayName("El cambio de rol entrega rol, bandera de alcance, sede y motivo tal como vinieron")
	void cambio_de_rol_entrega_los_parametros() throws Exception {
		given(membershipService.changeRole(any(), anyLong(), anyLong(), any(), anyBoolean(),
				any(), any())).willReturn(vinculo(10L, "ACTIVA"));

		mockMvc.perform(patch(BASE + "/10")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"roleCode":"RECEPCION","changeScope":true,
								 "consultorioId":null,"reason":"Pasa a toda la organizacion"}""")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(10));

		verify(membershipService).changeRole(ACTOR, 1L, 10L,
				"RECEPCION", true, null, "Pasa a toda la organizacion");
	}

	@Test
	@DisplayName("Cambiar solo el rol, sin mandar changeScope, llega al servicio con changeScope=false")
	void cambio_de_rol_sin_change_scope_no_toca_la_sede() throws Exception {
		// Defecto encontrado por este slice: con changeScope primitivo, Jackson 3 rechazaba el
		// campo ausente (FAIL_ON_NULL_FOR_PRIMITIVES) con un 400 de cuerpo ilegible, y el
		// contrato lo declara opcional con default false. Es exactamente el request que arma el
		// frontend para "cambiar el rol sin tocar la sede".
		given(membershipService.changeRole(any(), anyLong(), anyLong(), any(), anyBoolean(),
				any(), any())).willReturn(vinculo(10L, "ACTIVA"));

		mockMvc.perform(patch(BASE + "/10")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"roleCode\":\"PROFESIONAL\",\"reason\":\"Cambio de funciones\"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk());

		verify(membershipService).changeRole(ACTOR, 1L, 10L,
				"PROFESIONAL", false, null, "Cambio de funciones");
	}

	@Test
	@DisplayName("Sin motivo el cambio de rol es 400 con el campo senalado y no llega al servicio")
	void cambio_de_rol_sin_motivo_es_400() throws Exception {
		mockMvc.perform(patch(BASE + "/10")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"roleCode\":\"RECEPCION\"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.reason").exists());

		verify(membershipService, never()).changeRole(any(), anyLong(), anyLong(), any(),
				anyBoolean(), any(), any());
	}

	@Test
	@DisplayName("Cambiar un vinculo suspendido es 409 membership-not-active con los dos estados")
	void cambio_sobre_vinculo_no_activo_es_409() throws Exception {
		given(membershipService.changeRole(any(), anyLong(), anyLong(), any(), anyBoolean(),
				any(), any())).willThrow(new MembershipNotActiveException(
						10L, MembershipEstado.SUSPENDIDA, MembershipEstado.ACTIVA));

		mockMvc.perform(patch(BASE + "/10")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"roleCode\":\"RECEPCION\",\"reason\":\"Reasignacion\"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(
						"https://akine.app/problems/membership-not-active"))
				.andExpect(jsonPath("$.currentState").value("SUSPENDIDA"))
				.andExpect(jsonPath("$.requestedState").value("ACTIVA"));
	}

	@Test
	@DisplayName("Una version vieja del vinculo es 409 concurrent-modification, no 500")
	void cambio_concurrente_es_409() throws Exception {
		given(membershipService.changeRole(any(), anyLong(), anyLong(), any(), anyBoolean(),
				any(), any())).willThrow(
						new ObjectOptimisticLockingFailureException("Membership", 10L));

		mockMvc.perform(patch(BASE + "/10")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"roleCode\":\"RECEPCION\",\"reason\":\"Reasignacion\"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(
						"https://akine.app/problems/concurrent-modification"));
	}

	// =====================================================================================
	// Suspender, reactivar, revocar
	// =====================================================================================

	@Test
	@DisplayName("Suspender al ultimo administrador es 409 last-admin-required")
	void suspender_al_ultimo_admin_es_409() throws Exception {
		given(membershipService.suspend(any(), eq(1L), eq(10L), eq("Licencia")))
				.willThrow(new LastAdminException(1L));

		mockMvc.perform(post(BASE + "/10/suspend")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Licencia\"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(
						"https://akine.app/problems/last-admin-required"));
	}

	@Test
	@DisplayName("Reactivar devuelve el vinculo y le entrega el motivo al servicio")
	void reactivar_entrega_el_motivo() throws Exception {
		given(membershipService.reactivate(any(), anyLong(), anyLong(), any()))
				.willReturn(vinculo(10L, "ACTIVA"));

		mockMvc.perform(post(BASE + "/10/reactivate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Vuelve de licencia\"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("ACTIVA"));

		verify(membershipService).reactivate(
				ACTOR, 1L, 10L, "Vuelve de licencia");
	}

	@Test
	@DisplayName("Revocar sin motivo es 400: la operacion terminal exige cuerpo")
	void revocar_sin_motivo_es_400() throws Exception {
		mockMvc.perform(post(BASE + "/10/revoke")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"   \"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.reason").exists());

		verify(membershipService, never()).revoke(any(), anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("Revocar al fundador siendo otro administrador es 403, no 409")
	void revocar_al_fundador_es_403() throws Exception {
		given(membershipService.revoke(any(), anyLong(), anyLong(), any()))
				.willThrow(new FounderRevocationNotAllowedException(10L));

		mockMvc.perform(post(BASE + "/10/revoke")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Desvinculacion\"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"))
				.andExpect(jsonPath("$.title").value("Vinculo protegido"));
	}

	@Test
	@DisplayName("Revocarse el propio ultimo rol administrativo es 409 self-revoke-not-allowed")
	void revocarse_a_si_mismo_es_409() throws Exception {
		given(membershipService.revoke(any(), anyLong(), anyLong(), any()))
				.willThrow(new SelfRevokeNotAllowedException(7L));

		mockMvc.perform(post(BASE + "/10/revoke")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Me voy\"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(
						"https://akine.app/problems/self-revoke-not-allowed"));
	}

	// =====================================================================================
	// Permisos adicionales
	// =====================================================================================

	@Test
	@DisplayName("Otorgar un permiso adicional responde 201 y entrega codigo, motivo y vencimiento")
	void otorgar_permiso_es_201() throws Exception {
		given(membershipService.assignGrant(any(), anyLong(), anyLong(), any(), any(), any()))
				.willReturn(permiso("auditoria:read"));

		mockMvc.perform(post(BASE + "/10/grants")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"permissionCode":"auditoria:read","reason":"Auditoria interna",
								 "validUntil":"2026-12-31T23:59:59Z"}""")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.permissionCode").value("auditoria:read"))
				.andExpect(jsonPath("$.active").value(true));

		verify(membershipService).assignGrant(ACTOR, 1L, 10L,
				"auditoria:read", "Auditoria interna", Instant.parse("2026-12-31T23:59:59Z"));
	}

	@Test
	@DisplayName("Sin codigo de permiso ni motivo el otorgamiento es 400 con los dos campos")
	void otorgar_sin_campos_obligatorios_es_400() throws Exception {
		mockMvc.perform(post(BASE + "/10/grants")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.permissionCode").exists())
				.andExpect(jsonPath("$.errors.reason").exists());
	}

	@Test
	@DisplayName("Un codigo fuera del catalogo otorgable es 400 validation-error")
	void otorgar_permiso_no_otorgable_es_400() throws Exception {
		given(membershipService.assignGrant(any(), anyLong(), anyLong(), any(), any(), any()))
				.willThrow(new UnknownPermissionCodeException(
						"plataforma:admin", "El permiso no es otorgable en esta fase"));

		mockMvc.perform(post(BASE + "/10/grants")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"permissionCode\":\"plataforma:admin\",\"reason\":\"x\"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"))
				.andExpect(jsonPath("$.detail").value("El permiso no es otorgable en esta fase"));
	}

	@Test
	@DisplayName("Otorgar dos veces el mismo permiso es 409 grant-already-active con el codigo")
	void otorgar_permiso_duplicado_es_409() throws Exception {
		given(membershipService.assignGrant(any(), anyLong(), anyLong(), any(), any(), any()))
				.willThrow(new GrantAlreadyActiveException("auditoria:read", 10L));

		mockMvc.perform(post(BASE + "/10/grants")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"permissionCode\":\"auditoria:read\",\"reason\":\"x\"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(
						"https://akine.app/problems/grant-already-active"))
				.andExpect(jsonPath("$.permissionCode").value("auditoria:read"));
	}

	@Test
	@DisplayName("Quitar un permiso es 204 aun con Accept problem+json, y el motivo viaja por query")
	void quitar_permiso_es_204_con_accept_problem_json() throws Exception {
		mockMvc.perform(delete(BASE + "/10/grants/auditoria:read")
						.param("reason", "Fin de la auditoria")
						.accept(MediaType.APPLICATION_PROBLEM_JSON)
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isNoContent());

		verify(membershipService).revokeGrant(ACTOR, 1L, 10L,
				"auditoria:read", "Fin de la auditoria");
	}

	// =====================================================================================

	private static MembershipView vinculo(long id, String estado) {
		boolean revocado = "REVOCADA".equals(estado);
		return new MembershipView(
				id, 1L, 3L, 20L + id, "KINESIOLOGO", estado, false,
				Instant.parse("2026-01-15T13:45:00Z"),
				revocado ? Instant.parse("2026-03-01T10:00:00Z") : null,
				!revocado,
				revocado ? 7L : null,
				revocado ? "Renuncia" : null,
				"Ana Perez", "ana@example.com");
	}

	private static MembershipGrantView permiso(String codigo) {
		return new MembershipGrantView(
				30L, 10L, codigo, 7L, "Auditoria interna",
				Instant.parse("2026-02-01T10:00:00Z"), null, true);
	}
}
