package com.akine.organization.api;

import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.PedidoDeCambioDePlanService;
import com.akine.organization.application.PedidoDeCambioDePlanView;
import com.akine.organization.domain.PedidoDeCambioDePlan;
import com.akine.organization.domain.exception.PedidoDeCambioDePlanNotFoundException;
import com.akine.organization.domain.exception.PedidoDePlanPendienteException;
import com.akine.organization.domain.exception.PedidoExcedeLimitesException;
import com.akine.organization.domain.exception.PedidoExcedeLimitesException.Exceso;
import com.akine.organization.domain.exception.PedidoSinCambiosException;
import com.akine.organization.domain.exception.ServicioNoContratadoException;
import com.akine.organization.spi.Adicional;
import com.akine.organization.spi.LimitCode;
import com.akine.organization.spi.Servicio;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contrato HTTP del pedido de cambio de plan: centro y bandeja de plataforma (26/11/2026). */
@WebMvcTest(PedidoDeCambioDePlanController.class)
@Import(ApiSliceSecurityConfig.class)
class PedidoDeCambioDePlanControllerTest {

	private static final String CENTRO = "/api/v1/organizations/7/subscription/pedido-de-cambio";
	private static final String PLATAFORMA = "/api/v1/platform/pedidos-de-cambio-de-plan";
	private static final Instant CUANDO = Instant.parse("2026-11-26T12:00:00Z");

	@Autowired private MockMvc mockMvc;
	@MockitoBean private PedidoDeCambioDePlanService service;
	@MockitoBean private AuthorizationGuard authorizationGuard;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	private static PedidoDeCambioDePlanView pedido(PedidoDeCambioDePlan.Estado estado, List<Exceso> excesos) {
		return new PedidoDeCambioDePlanView(5, 7, "Centro Norte", "CRECIMIENTO", "Plan Intermedio",
				Set.of(Servicio.KINESIOLOGIA, Servicio.values()[Servicio.values().length - 1]), Set.of(Adicional.IA),
				"BASE", "Plan Base", Set.of(Servicio.KINESIOLOGIA), Set.of(), "Sumamos yoga", estado,
				estado == PedidoDeCambioDePlan.Estado.RECHAZADO ? "Falta el pago" : null, CUANDO,
				estado == PedidoDeCambioDePlan.Estado.PENDIENTE ? null : CUANDO, 2, excesos);
	}

	private static final String CUERPO_PEDIDO = """
			{"planCode":"CRECIMIENTO","servicios":["KINESIOLOGIA"],"adicionales":["IA"],"comentario":"Sumamos yoga"}""";

	// --- Centro ---------------------------------------------------------------------------------

	@Test
	@DisplayName("El centro ve su ultimo pedido con lo que tenia al pedir")
	void ver_ultimo() throws Exception {
		given(service.ultimo(7)).willReturn(Optional.of(pedido(PedidoDeCambioDePlan.Estado.PENDIENTE,
				List.of(new Exceso(LimitCode.MAX_CONSULTORIOS, 1, 3)))));

		mockMvc.perform(get(CENTRO).with(ApiActors.miembro(3L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.planCode").value("CRECIMIENTO"))
				.andExpect(jsonPath("$.planNombreAnterior").value("Plan Base"))
				.andExpect(jsonPath("$.servicios[0]").value("KINESIOLOGIA"))
				.andExpect(jsonPath("$.adicionales[0]").value("IA"))
				.andExpect(jsonPath("$.excesos[0].limitCode").value("MAX_CONSULTORIOS"))
				.andExpect(jsonPath("$.excesos[0].uso").value(3))
				.andExpect(jsonPath("$.estado").value("PENDIENTE"));
	}

	@Test
	@DisplayName("Si el centro nunca pidio, 204")
	void ver_sin_pedidos() throws Exception {
		given(service.ultimo(7)).willReturn(Optional.empty());

		mockMvc.perform(get(CENTRO).with(ApiActors.miembro(3L))).andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("Quien no edita la organizacion no ve el pedido")
	void ver_sin_permiso() throws Exception {
		willThrow(new AccessDeniedException("no")).given(authorizationGuard)
				.requireOrgDataEditor(anyLong(), anyLong(), any(), anyBoolean());

		mockMvc.perform(get(CENTRO).with(ApiActors.miembro(3L))).andExpect(status().isForbidden());

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("El centro pide un cambio y queda pendiente")
	void pedir() throws Exception {
		given(service.pedir(eq(7L), eq("CRECIMIENTO"), any(), any(), eq("Sumamos yoga"), eq(3L)))
				.willReturn(pedido(PedidoDeCambioDePlan.Estado.PENDIENTE, List.of()));

		mockMvc.perform(post(CENTRO).with(ApiActors.miembro(3L))
						.contentType(MediaType.APPLICATION_JSON).content(CUERPO_PEDIDO))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.id").value(5))
				.andExpect(jsonPath("$.comentario").value("Sumamos yoga"));
	}

	@Test
	@DisplayName("Pedir sin servicios es 400 y no llega al servicio")
	void pedir_sin_servicios() throws Exception {
		mockMvc.perform(post(CENTRO).with(ApiActors.miembro(3L)).contentType(MediaType.APPLICATION_JSON)
						.content("{\"planCode\":\"CRECIMIENTO\",\"servicios\":[],\"adicionales\":[]}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("Pedir lo mismo que se tiene es 400 pedido-sin-cambios")
	void pedir_sin_cambios() throws Exception {
		given(service.pedir(anyLong(), anyString(), any(), any(), any(), anyLong()))
				.willThrow(new PedidoSinCambiosException());

		mockMvc.perform(post(CENTRO).with(ApiActors.miembro(3L))
						.contentType(MediaType.APPLICATION_JSON).content(CUERPO_PEDIDO))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("pedido-sin-cambios")));
	}

	@Test
	@DisplayName("Con un pedido esperando, otro es 409 pedido-de-plan-pendiente")
	void pedir_con_uno_pendiente() throws Exception {
		given(service.pedir(anyLong(), anyString(), any(), any(), any(), anyLong()))
				.willThrow(new PedidoDePlanPendienteException());

		mockMvc.perform(post(CENTRO).with(ApiActors.miembro(3L))
						.contentType(MediaType.APPLICATION_JSON).content(CUERPO_PEDIDO))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("pedido-de-plan-pendiente")));
	}

	@Test
	@DisplayName("Un servicio dejado responde 409 con soloLectura y como volver a sumarlo")
	void servicio_en_solo_lectura() throws Exception {
		given(service.ultimo(7)).willThrow(new ServicioNoContratadoException(Servicio.KINESIOLOGIA, true));

		mockMvc.perform(get(CENTRO).with(ApiActors.miembro(3L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.soloLectura").value(true))
				.andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Suscripcion")));
	}

	@Test
	@DisplayName("El centro cancela su pedido con la version que leyo")
	void cancelar() throws Exception {
		given(service.cancelar(7, 5, 2, 3)).willReturn(pedido(PedidoDeCambioDePlan.Estado.CANCELADO, List.of()));

		mockMvc.perform(post(CENTRO + "/5/cancelar").with(ApiActors.miembro(3L))
						.contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("CANCELADO"));
	}

	@Test
	@DisplayName("Cancelar un pedido de otro centro es 404")
	void cancelar_inexistente() throws Exception {
		given(service.cancelar(anyLong(), anyLong(), anyLong(), anyLong()))
				.willThrow(new PedidoDeCambioDePlanNotFoundException(5));

		mockMvc.perform(post(CENTRO + "/5/cancelar").with(ApiActors.miembro(3L))
						.contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
				.andExpect(status().isNotFound());
	}

	// --- Plataforma -----------------------------------------------------------------------------

	@Test
	@DisplayName("La plataforma lista la bandeja con el total de pendientes y acota la pagina")
	void listar() throws Exception {
		given(service.listar(eq(PedidoDeCambioDePlan.Estado.PENDIENTE), eq("norte"), any(Pageable.class)))
				.willAnswer(i -> new PageImpl<>(List.of(pedido(PedidoDeCambioDePlan.Estado.PENDIENTE, List.of())),
						i.getArgument(2), 1));
		given(service.pendientes()).willReturn(4L);

		mockMvc.perform(get(PLATAFORMA).with(ApiActors.platformAdmin(1L))
						.param("estado", "PENDIENTE").param("q", "norte").param("page", "-3").param("size", "5000"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].organizationNombre").value("Centro Norte"))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.pendientes").value(4));

		then(service).should().listar(eq(PedidoDeCambioDePlan.Estado.PENDIENTE), eq("norte"),
				eq(PageRequest.of(0, ApiPaging.TAMANO_MAXIMO)));
	}

	@Test
	@DisplayName("Sin estado ni texto, la bandeja trae todos")
	void listar_todos() throws Exception {
		given(service.listar(isNull(), isNull(), any(Pageable.class)))
				.willAnswer(i -> new PageImpl<>(List.of(), i.getArgument(2), 0));

		mockMvc.perform(get(PLATAFORMA).with(ApiActors.platformAdmin(1L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").isEmpty());
	}

	@Test
	@DisplayName("Sin rol de plataforma no se ve la bandeja")
	void listar_sin_rol() throws Exception {
		willThrow(new AccessDeniedException("no")).given(authorizationGuard).requirePlatformAdmin(anyBoolean());

		mockMvc.perform(get(PLATAFORMA).with(ApiActors.miembro(3L))).andExpect(status().isForbidden());

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("La plataforma aprueba y el pedido queda aprobado")
	void aprobar() throws Exception {
		given(service.aprobar(5, 2, 1)).willReturn(pedido(PedidoDeCambioDePlan.Estado.APROBADO, List.of()));

		mockMvc.perform(post(PLATAFORMA + "/5/aprobar").with(ApiActors.platformAdmin(1L))
						.contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("APROBADO"))
				.andExpect(jsonPath("$.resueltoEn").exists());
	}

	@Test
	@DisplayName("Aprobar con topes excedidos es 409 con los excesos")
	void aprobar_con_excesos() throws Exception {
		given(service.aprobar(anyLong(), anyLong(), anyLong())).willThrow(new PedidoExcedeLimitesException(
				List.of(new Exceso(LimitCode.MAX_CONSULTORIOS, 1, 3), new Exceso(LimitCode.MAX_PROFESIONALES, 2, 4))));

		mockMvc.perform(post(PLATAFORMA + "/5/aprobar").with(ApiActors.platformAdmin(1L))
						.contentType(MediaType.APPLICATION_JSON).content("{\"version\":2}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("pedido-excede-limites")))
				.andExpect(jsonPath("$.excesos[0].limitCode").value("MAX_CONSULTORIOS"))
				.andExpect(jsonPath("$.excesos[1].tope").value(2))
				.andExpect(jsonPath("$.excesos[1].uso").value(4));
	}

	@Test
	@DisplayName("La plataforma rechaza con motivo")
	void rechazar() throws Exception {
		given(service.rechazar(5, "Falta el pago", 2, 1))
				.willReturn(pedido(PedidoDeCambioDePlan.Estado.RECHAZADO, List.of()));

		mockMvc.perform(post(PLATAFORMA + "/5/rechazar").with(ApiActors.platformAdmin(1L))
						.contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Falta el pago\",\"version\":2}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("RECHAZADO"))
				.andExpect(jsonPath("$.motivoRechazo").value("Falta el pago"));
	}

	@Test
	@DisplayName("Rechazar sin motivo es 400")
	void rechazar_sin_motivo() throws Exception {
		mockMvc.perform(post(PLATAFORMA + "/5/rechazar").with(ApiActors.platformAdmin(1L))
						.contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"\",\"version\":2}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(service);
	}
}
