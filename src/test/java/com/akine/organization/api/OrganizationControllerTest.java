package com.akine.organization.api;

import com.akine.organization.application.ConsultorioView;
import com.akine.organization.application.OrganizationService;
import com.akine.organization.application.OrganizationView;
import com.akine.organization.application.PlanNotFoundException;
import com.akine.organization.application.ProvisionalAuthorizationGuard;
import com.akine.organization.domain.OperationalStatus;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de la administracion del tenant (RF-M01-001, RF-M01-003, RF-M01-004).
 *
 * <p>Lo que se verifica aca es exclusivamente la capa {@code api}: que el request se valide en
 * formato, que la autorizacion se delegue siempre en {@link ProvisionalAuthorizationGuard}, y
 * que cada excepcion salga con el codigo y el cuerpo que el contrato promete. Las reglas de
 * negocio son de {@code application} y tienen sus propios tests.
 */
@WebMvcTest(OrganizationController.class)
@Import(ApiSliceSecurityConfig.class)
class OrganizationControllerTest {

	private static final String IDEMPOTENCY_KEY = "0f9d5f6e-1c2b-4a3d-9e8f-7a6b5c4d3e2f";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrganizationService organizationService;

	@MockitoBean
	private ProvisionalAuthorizationGuard authorizationGuard;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	// =====================================================================================
	// POST /api/v1/organizations
	// =====================================================================================

	@Test
	@DisplayName("El alta devuelve 201 con el recurso y la cabecera Location del tenant nuevo")
	void alta_devuelve_201_con_location() throws Exception {
		given(organizationService.create(any(), any(), any(), any(), any()))
				.willReturn(vista(5L, 0L));

		mockMvc.perform(post("/api/v1/organizations")
						.header("Idempotency-Key", IDEMPOTENCY_KEY)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Centro Kinesico Belgrano",
								 "slug":"centro-kinesico-belgrano",
								 "timezone":"America/Argentina/Buenos_Aires",
								 "planCode":"BASICO"}""")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/organizations/5"))
				.andExpect(jsonPath("$.id").value(5))
				.andExpect(jsonPath("$.slug").value("centro-kinesico-belgrano"))
				.andExpect(jsonPath("$.version").value(0))
				.andExpect(jsonPath("$.operationalStatus").value("ACTIVA"));

		verify(organizationService).create(
				"Centro Kinesico Belgrano",
				"centro-kinesico-belgrano",
				"America/Argentina/Buenos_Aires",
				"BASICO",
				1L);
	}

	@Test
	@DisplayName("El alta esta reservada a la administracion de plataforma: sin el flag es 403")
	void alta_sin_platform_admin_es_403() throws Exception {
		willThrow(new AccessDeniedException("Operacion reservada a la administracion de plataforma"))
				.given(authorizationGuard).requirePlatformAdmin(false);

		mockMvc.perform(post("/api/v1/organizations")
						.header("Idempotency-Key", IDEMPOTENCY_KEY)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Centro\",\"planCode\":\"BASICO\"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"));

		verify(organizationService, never()).create(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("Sin sesion autenticada el alta responde 403 y no llega al guard")
	void alta_sin_sesion_es_403() throws Exception {
		mockMvc.perform(post("/api/v1/organizations")
						.header("Idempotency-Key", IDEMPOTENCY_KEY)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Centro\",\"planCode\":\"BASICO\"}"))
				.andExpect(status().isForbidden());

		verify(authorizationGuard, never()).requirePlatformAdmin(anyBoolean());
	}

	@Test
	@DisplayName("Falta el header Idempotency-Key: 400 nombrando el header que falta")
	void alta_sin_idempotency_key_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/organizations")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Centro\",\"planCode\":\"BASICO\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"))
				.andExpect(jsonPath("$.detail").value(
						"Falta el header obligatorio 'Idempotency-Key'"));
	}

	@Test
	@DisplayName("Un Idempotency-Key en blanco se rechaza igual que si faltara")
	void alta_con_idempotency_key_en_blanco_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/organizations")
						.header("Idempotency-Key", "   ")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Centro\",\"planCode\":\"BASICO\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.status").value(400));

		verify(organizationService, never()).create(any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("Nombre vacio y plan ausente devuelven 400 con el detalle por campo")
	void alta_con_campos_invalidos_detalla_los_campos() throws Exception {
		mockMvc.perform(post("/api/v1/organizations")
						.header("Idempotency-Key", IDEMPOTENCY_KEY)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"  \"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"))
				.andExpect(jsonPath("$.errors.name").exists())
				.andExpect(jsonPath("$.errors.planCode").exists());
	}

	@Test
	@DisplayName("Un slug mas largo que el maximo del contrato se rechaza con 400")
	void alta_con_slug_demasiado_largo_es_400() throws Exception {
		String slugLargo = "s".repeat(65);

		mockMvc.perform(post("/api/v1/organizations")
						.header("Idempotency-Key", IDEMPOTENCY_KEY)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Centro\",\"slug\":\"" + slugLargo
								+ "\",\"planCode\":\"BASICO\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.slug").exists());
	}

	@Test
	@DisplayName("Un plan no contratable responde 404 generico, sin decir si existio alguna vez")
	void alta_con_plan_no_contratable_es_404() throws Exception {
		given(organizationService.create(any(), any(), any(), any(), any()))
				.willThrow(new PlanNotFoundException("INEXISTENTE"));

		mockMvc.perform(post("/api/v1/organizations")
						.header("Idempotency-Key", IDEMPOTENCY_KEY)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Centro\",\"planCode\":\"INEXISTENTE\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"))
				.andExpect(jsonPath("$.detail").value(
						"El recurso solicitado no existe o no esta disponible."))
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("INEXISTENTE"))));
	}

	@Test
	@DisplayName("Un slug ya usado por otro tenant responde 409 sin filtrar el indice de la base")
	void alta_con_slug_duplicado_es_409() throws Exception {
		given(organizationService.create(any(), any(), any(), any(), any()))
				.willThrow(new DataIntegrityViolationException(
						"Duplicate entry 'centro' for key 'uk_organization_slug'"));

		mockMvc.perform(post("/api/v1/organizations")
						.header("Idempotency-Key", IDEMPOTENCY_KEY)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Centro\",\"planCode\":\"BASICO\"}")
						.with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isConflict())
				.andExpect(content().string(org.hamcrest.Matchers.not(
						org.hamcrest.Matchers.containsString("uk_organization_slug"))));
	}

	// =====================================================================================
	// GET /api/v1/organizations/{orgId}
	// =====================================================================================

	@Test
	@DisplayName("La lectura devuelve el tenant con la version que hay que reenviar para editarlo")
	void lectura_devuelve_la_organizacion_con_su_version() throws Exception {
		given(organizationService.find(1L)).willReturn(vista(1L, 3L));

		mockMvc.perform(get("/api/v1/organizations/1").with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(1))
				.andExpect(jsonPath("$.name").value("Centro Kinesico Belgrano"))
				.andExpect(jsonPath("$.timezone").value("America/Argentina/Buenos_Aires"))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.version").value(3));

		verify(authorizationGuard).requireMember(7L, 1L, null, false);
	}

	@Test
	@DisplayName("Una organizacion de otro tenant responde 404, jamas 403: un 403 confirmaria que existe")
	void lectura_cross_tenant_es_404() throws Exception {
		willThrow(new OrganizationNotFoundException(99L))
				.given(authorizationGuard).requireMember(eq(7L), eq(99L), any(), anyBoolean());

		mockMvc.perform(get("/api/v1/organizations/99").with(ApiActors.miembro(7L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"))
				.andExpect(jsonPath("$.title").value("Recurso no encontrado"));

		verify(organizationService, never()).find(anyLong());
	}

	@Test
	@DisplayName("Sin sesion autenticada la lectura responde 403 y jamas 401")
	void lectura_sin_sesion_es_403_nunca_401() throws Exception {
		mockMvc.perform(get("/api/v1/organizations/1"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value(403))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"));
	}

	@Test
	@DisplayName("El contexto validado del holder es lo que llega al guard, no un claim del token")
	void el_contexto_del_holder_es_el_que_autoriza() throws Exception {
		given(tenantContextHolder.current()).willReturn(Optional.of(
				new com.akine.platform.spi.tenant.RequestTenantContext(
						7L, 1L, 10L, "ORG_ADMIN",
						com.akine.platform.spi.tenant.TenantOperationalStatus.ACTIVA)));
		given(organizationService.find(1L)).willReturn(vista(1L, 0L));

		mockMvc.perform(get("/api/v1/organizations/1").with(ApiActors.miembro(7L)))
				.andExpect(status().isOk());

		verify(authorizationGuard).requireMember(7L, 1L, 1L, false);
	}

	// =====================================================================================
	// PATCH /api/v1/organizations/{orgId}
	// =====================================================================================

	@Test
	@DisplayName("La edicion devuelve la organizacion con la version nueva")
	void edicion_devuelve_la_version_nueva() throws Exception {
		given(organizationService.update(eq(1L), any(), any(), eq(3L), any()))
				.willReturn(vista(1L, 4L));

		mockMvc.perform(patch("/api/v1/organizations/1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"name":"Centro Kinesico Belgrano Norte",
								 "timezone":"America/Argentina/Cordoba",
								 "version":3}""")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(4));

		verify(authorizationGuard).requireOrgAdmin(7L, 1L, null, false);
		verify(organizationService).update(
				1L, "Centro Kinesico Belgrano Norte", "America/Argentina/Cordoba", 3L, 7L);
	}

	@Test
	@DisplayName("Omitir la version es 400: un cero implicito acertaria la version inicial por casualidad")
	void edicion_sin_version_es_400() throws Exception {
		mockMvc.perform(patch("/api/v1/organizations/1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Centro\"}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.version").exists());

		verify(organizationService, never()).update(anyLong(), any(), any(), anyLong(), any());
	}

	@Test
	@DisplayName("Una version negativa se rechaza con 400 antes de tocar la base")
	void edicion_con_version_negativa_es_400() throws Exception {
		mockMvc.perform(patch("/api/v1/organizations/1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Centro\",\"version\":-1}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errors.version").exists());
	}

	@Test
	@DisplayName("Una version vieja responde 409: releer y reintentar, nunca pisar el cambio ajeno")
	void edicion_con_version_vieja_es_409() throws Exception {
		given(organizationService.update(eq(1L), any(), any(), eq(2L), any()))
				.willThrow(new OptimisticLockingFailureException(
						"La organizacion fue modificada por otra operacion"));

		mockMvc.perform(patch("/api/v1/organizations/1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Centro\",\"version\":2}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.status").value(409))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/conflict"))
				.andExpect(jsonPath("$.title").value("Conflicto de concurrencia"));
	}

	@Test
	@DisplayName("Estar en el tenant correcto sin administrarlo es 403: negarlo no revela nada nuevo")
	void edicion_sin_rol_de_administrador_es_403() throws Exception {
		willThrow(new AccessDeniedException("Se requiere administrar la organizacion"))
				.given(authorizationGuard).requireOrgAdmin(eq(7L), eq(1L), any(), anyBoolean());

		mockMvc.perform(patch("/api/v1/organizations/1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Centro\",\"version\":0}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/forbidden"))
				.andExpect(jsonPath("$.detail").value(
						"No tiene permiso para realizar esta operacion"));
	}

	@Test
	@DisplayName("Editar una organizacion dada de baja o ajena responde 404 generico")
	void edicion_de_organizacion_no_accesible_es_404() throws Exception {
		given(organizationService.update(eq(1L), any(), any(), anyLong(), any()))
				.willThrow(new OrganizationNotFoundException(1L));

		mockMvc.perform(patch("/api/v1/organizations/1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"version\":0}")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"));
	}

	// =====================================================================================
	// GET /api/v1/organizations/{orgId}/consultorios
	// =====================================================================================

	@Test
	@DisplayName("El listado de sedes devuelve la pagina pedida con sus totales")
	void listado_de_sedes_devuelve_la_pagina() throws Exception {
		given(organizationService.consultorios(1L)).willReturn(List.of(
				new ConsultorioView(10L, 1L, "Sede Central", true),
				new ConsultorioView(11L, 1L, "Sede Norte", true)));

		mockMvc.perform(get("/api/v1/organizations/1/consultorios").with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].id").value(10))
				.andExpect(jsonPath("$.content[0].organizationId").value(1))
				.andExpect(jsonPath("$.content[0].name").value("Sede Central"))
				.andExpect(jsonPath("$.content[0].active").value(true))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.totalElements").value(2))
				.andExpect(jsonPath("$.totalPages").value(1));

		verify(authorizationGuard).requireMember(7L, 1L, null, false);
	}

	@Test
	@DisplayName("Un size por encima del tope se acota a 100, no se rechaza con 400")
	void size_excesivo_se_acota_al_maximo() throws Exception {
		given(organizationService.consultorios(1L)).willReturn(List.of(
				new ConsultorioView(10L, 1L, "Sede Central", true)));

		mockMvc.perform(get("/api/v1/organizations/1/consultorios")
						.param("size", "5000")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.size").value(ApiPaging.TAMANO_MAXIMO))
				.andExpect(jsonPath("$.content.length()").value(1));
	}

	@Test
	@DisplayName("Una pagina negativa o un size cero se normalizan en vez de fallar")
	void parametros_de_paginado_absurdos_se_normalizan() throws Exception {
		given(organizationService.consultorios(1L)).willReturn(List.of(
				new ConsultorioView(10L, 1L, "Sede Central", true)));

		mockMvc.perform(get("/api/v1/organizations/1/consultorios")
						.param("page", "-3")
						.param("size", "0")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(1))
				.andExpect(jsonPath("$.content.length()").value(1));
	}

	@Test
	@DisplayName("Una pagina fuera de rango devuelve contenido vacio, no un error")
	void pagina_fuera_de_rango_devuelve_contenido_vacio() throws Exception {
		given(organizationService.consultorios(1L)).willReturn(List.of(
				new ConsultorioView(10L, 1L, "Sede Central", true)));

		mockMvc.perform(get("/api/v1/organizations/1/consultorios")
						.param("page", "10")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(0))
				.andExpect(jsonPath("$.page").value(10))
				.andExpect(jsonPath("$.totalElements").value(1));
	}

	@Test
	@DisplayName("La segunda pagina trae el recorte correcto del listado completo")
	void la_segunda_pagina_recorta_el_listado() throws Exception {
		given(organizationService.consultorios(1L)).willReturn(
				IntStream.rangeClosed(1, 5)
						.mapToObj(i -> new ConsultorioView(i, 1L, "Sede " + i, true))
						.toList());

		mockMvc.perform(get("/api/v1/organizations/1/consultorios")
						.param("page", "1")
						.param("size", "2")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content.length()").value(2))
				.andExpect(jsonPath("$.content[0].id").value(3))
				.andExpect(jsonPath("$.content[1].id").value(4))
				.andExpect(jsonPath("$.totalElements").value(5))
				.andExpect(jsonPath("$.totalPages").value(3));
	}

	@Test
	@DisplayName("El listado de sedes de un tenant ajeno responde 404, igual que si no existiera")
	void listado_de_sedes_cross_tenant_es_404() throws Exception {
		willThrow(new OrganizationNotFoundException(99L))
				.given(authorizationGuard).requireMember(eq(7L), eq(99L), any(), anyBoolean());

		mockMvc.perform(get("/api/v1/organizations/99/consultorios").with(ApiActors.miembro(7L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"));

		verify(organizationService, never()).consultorios(anyLong());
	}

	@Test
	@DisplayName("Sin sesion autenticada el listado de sedes responde 403 y jamas 401")
	void listado_de_sedes_sin_sesion_es_403() throws Exception {
		mockMvc.perform(get("/api/v1/organizations/1/consultorios"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.status").value(403));
	}

	@Test
	@DisplayName("Un identificador de organizacion no numerico responde 400, no 500")
	void orgid_no_numerico_es_400() throws Exception {
		mockMvc.perform(get("/api/v1/organizations/no-es-un-numero")
						.with(ApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"));
	}

	private static OrganizationView vista(long id, long version) {
		return new OrganizationView(
				id,
				"Centro Kinesico Belgrano",
				"centro-kinesico-belgrano",
				"America/Argentina/Buenos_Aires",
				true,
				version,
				OperationalStatus.ACTIVA);
	}
}
