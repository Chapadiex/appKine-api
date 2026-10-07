package com.akine.offering.api;

import com.akine.offering.application.OfertaPracticaService;
import com.akine.offering.application.PracticasDeOfertaView;
import com.akine.offering.domain.exception.OfertaNotAccessibleException;
import com.akine.offering.domain.exception.PracticaNoElegibleException;
import com.akine.offering.domain.exception.PracticaPrincipalInvalidaException;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.tenant.TenantOperationalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La capa REST del puente Oferta-Practica (A-9): que cada excepcion del servicio salga con el
 * codigo y el {@code type} que el contrato promete. Las reglas viven en el servicio.
 */
@WebMvcTest(OfertaPracticaController.class)
@Import({OfferingApiSliceSecurityConfig.class, OfferingApiActor.class})
@DisplayName("OfertaPracticaController")
class OfertaPracticaControllerTest {

	private static final long CONSULTORIO_ID = 3L;
	private static final long ORG_ID = 1L;
	private static final long CUENTA = 99L;
	private static final String RUTA = "/api/v1/consultorios/3/ofertas/34/practicas";

	@Autowired private MockMvc mockMvc;

	@MockitoBean private OfertaPracticaService practicaService;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@BeforeEach
	void setUp() {
		given(tenantContextHolder.current()).willReturn(Optional.of(new RequestTenantContext(
				CUENTA, ORG_ID, CONSULTORIO_ID, "ORG_ADMIN", TenantOperationalStatus.ACTIVA)));
	}

	@Test
	@DisplayName("GET publica la principal y la version de la oferta")
	void lectura() throws Exception {
		given(practicaService.leer(any(), eq(ORG_ID), eq(CONSULTORIO_ID), eq(34L)))
				.willReturn(new PracticasDeOfertaView(34L, 5L, 51L, List.of(
						new PracticasDeOfertaView.PracticaDeOfertaView(
								1L, 51L, "P-51", "Practica", true, "ACTIVO", true, null, null, 0L))));

		mockMvc.perform(get(RUTA).with(autenticado()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ofertaVersion").value(5))
				.andExpect(jsonPath("$.practicaPrincipalId").value(51))
				.andExpect(jsonPath("$.practicas[0].principal").value(true));
	}

	@Test
	@DisplayName("PUT pasa la lista, la principal y la version tal cual")
	void reemplazo() throws Exception {
		given(practicaService.reemplazar(any(), anyLong(), anyLong(), anyLong(), any(), any(),
				anyLong())).willReturn(new PracticasDeOfertaView(34L, 6L, 51L, List.of()));

		mockMvc.perform(put(RUTA).with(autenticado()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"practicaIds\":[51,52],\"practicaPrincipalId\":51,\"expectedVersion\":5}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ofertaVersion").value(6));

		verify(practicaService).reemplazar(any(), eq(ORG_ID), eq(CONSULTORIO_ID), eq(34L),
				eq(List.of(51L, 52L)), eq(51L), eq(5L));
	}

	@Test
	@DisplayName("sin la lista es 400: vacia se manda vacia, no se omite")
	void sin_lista() throws Exception {
		mockMvc.perform(put(RUTA).with(autenticado()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":5}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("principal incoherente: 400 validation-error")
	void principal_invalida() throws Exception {
		given(practicaService.reemplazar(any(), anyLong(), anyLong(), anyLong(), any(), any(),
				anyLong())).willThrow(new PracticaPrincipalInvalidaException("no esta en la lista"));

		mockMvc.perform(put(RUTA).with(autenticado()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"practicaIds\":[51],\"practicaPrincipalId\":52,\"expectedVersion\":5}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("validation-error")));
	}

	@Test
	@DisplayName("oferta de otro tenant: 404")
	void oferta_ajena() throws Exception {
		given(practicaService.leer(any(), anyLong(), anyLong(), anyLong()))
				.willThrow(new OfertaNotAccessibleException(34L));

		mockMvc.perform(get(RUTA).with(autenticado()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("not-found")));
	}

	@Test
	@DisplayName("practica ajena 404; practica dada de baja 409 practica-no-utilizable")
	void practica_no_elegible() throws Exception {
		given(practicaService.reemplazar(any(), anyLong(), anyLong(), anyLong(), any(), any(),
				anyLong()))
				.willThrow(new PracticaNoElegibleException(99L, PracticaNoElegibleException.Motivo.INEXISTENTE))
				.willThrow(new PracticaNoElegibleException(52L, PracticaNoElegibleException.Motivo.NO_VIGENTE));
		String cuerpo = "{\"practicaIds\":[52],\"practicaPrincipalId\":52,\"expectedVersion\":5}";

		mockMvc.perform(put(RUTA).with(autenticado()).contentType(MediaType.APPLICATION_JSON)
						.content(cuerpo))
				.andExpect(status().isNotFound());
		mockMvc.perform(put(RUTA).with(autenticado()).contentType(MediaType.APPLICATION_JSON)
						.content(cuerpo))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type")
						.value(org.hamcrest.Matchers.endsWith("practica-no-utilizable")))
				.andExpect(jsonPath("$.practicaId").value(52));
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor autenticado() {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(CUENTA), "n/a", List.of()));
	}

	/** Principal minimo: el slice no evalua permisos. */
	private record PrincipalDePrueba(long accountId) implements AuthenticatedPrincipal {

		@Override
		public boolean platformAdmin() {
			return false;
		}

		@Override
		public Long organizationId() {
			return ORG_ID;
		}

		@Override
		public Long consultorioId() {
			return CONSULTORIO_ID;
		}
	}
}
