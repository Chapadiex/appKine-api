package com.akine.organization.api;

import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.SolicitudDePruebaService;
import com.akine.organization.application.SolicitudDePruebaView;
import com.akine.organization.domain.SolicitudDePrueba;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP de los pedidos de prueba gratis: el publico de la landing y la bandeja de plataforma. */
@WebMvcTest(SolicitudDePruebaController.class)
@Import(ApiSliceSecurityConfig.class)
class SolicitudDePruebaControllerTest {

	private static final String PUBLICO = "/api/v1/public/solicitudes-de-prueba";
	private static final String BANDEJA = "/api/v1/platform/solicitudes-de-prueba";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private SolicitudDePruebaService service;

	@MockitoBean
	private AuthorizationGuard authorizationGuard;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@Test
	@DisplayName("El pedido publico valido responde 202 y llega entero al servicio con su clave")
	void pedido_publico_202() throws Exception {
		mockMvc.perform(post(PUBLICO).header("Idempotency-Key", "k-1")
						.contentType(MediaType.APPLICATION_JSON).content(pedido(true, false)))
				.andExpect(status().isAccepted());

		ArgumentCaptor<SolicitudDePruebaService.Pedido> captor =
				ArgumentCaptor.forClass(SolicitudDePruebaService.Pedido.class);
		verify(service).recibir(captor.capture(), eq("k-1"));
		assertThat(captor.getValue().email()).isEqualTo("ana@centro.test");
		assertThat(captor.getValue().kinesiologia()).isTrue();
		assertThat(captor.getValue().sedes()).isEqualTo(SolicitudDePrueba.Sedes.HASTA_TRES);
	}

	@Test
	@DisplayName("Sin ningun servicio elegido el pedido es 400 y no se guarda")
	void sin_servicio_400() throws Exception {
		mockMvc.perform(post(PUBLICO).header("Idempotency-Key", "k-2")
						.contentType(MediaType.APPLICATION_JSON).content(pedido(false, false)))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("La bandeja acota el paginado y trae el total de pendientes")
	void bandeja() throws Exception {
		given(service.listar(eq(SolicitudDePrueba.Estado.PENDIENTE), eq("mov"), any()))
				.willReturn(new PageImpl<>(List.of(vista()), PageRequest.of(0, 100), 1));
		given(service.pendientes()).willReturn(5L);

		mockMvc.perform(get(BANDEJA).param("estado", "PENDIENTE").param("q", "mov")
						.param("page", "-2").param("size", "9999").with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(9))
				.andExpect(jsonPath("$.content[0].nombreCentro").value("Centro Movimiento"))
				.andExpect(jsonPath("$.content[0].estado").value("PENDIENTE"))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.pendientes").value(5));

		ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
		verify(service).listar(any(), any(), pagina.capture());
		assertThat(pagina.getValue().getPageNumber()).isZero();
		assertThat(pagina.getValue().getPageSize()).isEqualTo(ApiPaging.TAMANO_MAXIMO);
	}

	@Test
	@DisplayName("Ver un pedido y descartarlo con motivo y version")
	void ver_y_descartar() throws Exception {
		given(service.ver(9L)).willReturn(vista());
		given(service.descartar(9L, "Duplicado", 0L, 1L)).willReturn(vista());

		mockMvc.perform(get(BANDEJA + "/9").with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.email").value("ana@centro.test"));

		mockMvc.perform(post(BANDEJA + "/9/descartar").with(ApiActors.platformAdmin(1L))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"motivo\":\"Duplicado\",\"version\":0}"))
				.andExpect(status().isOk());

		verify(service).descartar(9L, "Duplicado", 0L, 1L);
	}

	@Test
	@DisplayName("Sin rol de plataforma la bandeja responde 403 sin leer nada")
	void bandeja_sin_rol_403() throws Exception {
		willThrow(new AccessDeniedException("no")).given(authorizationGuard).requirePlatformAdmin(anyBoolean());

		mockMvc.perform(get(BANDEJA).with(ApiActors.miembro(3L)))
				.andExpect(status().isForbidden());

		verifyNoInteractions(service);
	}

	private static String pedido(boolean kinesiologia, boolean actividades) {
		return """
				{"nombre":"Ana","apellido":"Gomez","email":"ana@centro.test","nombreCentro":"Centro Movimiento",
				 "kinesiologia":%s,"actividades":%s,"sedes":"HASTA_TRES","interesaIa":true}"""
				.formatted(kinesiologia, actividades);
	}

	private static SolicitudDePruebaView vista() {
		return new SolicitudDePruebaView(9L, "Ana", "Gomez", "ana@centro.test", null, "Centro Movimiento",
				"Cordoba", true, false, SolicitudDePrueba.Sedes.HASTA_TRES, true, null,
				SolicitudDePrueba.Estado.PENDIENTE, null, null, Instant.parse("2026-11-18T12:00:00Z"), null, 0L);
	}
}
