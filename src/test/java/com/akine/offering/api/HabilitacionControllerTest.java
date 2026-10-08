package com.akine.offering.api;

import com.akine.offering.application.HabilitacionesView;
import com.akine.offering.application.OfertaHabilitacionService;
import com.akine.offering.application.ValidacionDeOfertaView;
import com.akine.offering.domain.exception.HabilitacionNoAccesibleException;
import com.akine.offering.domain.exception.OfertaInactivaException;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La capa REST de las habilitaciones de una oferta (02.07).
 *
 * <p>Lo que se fija es lo que el controller decide por su cuenta: que sin contexto de trabajo
 * no se llega al servicio, que la lista llega como CONJUNTO —sin duplicados ni nulos, y vacia
 * cuando se manda vacia—, que la validacion pasa los parametros opcionales como {@code null} y
 * no como cero, y que los errores del dominio salen con su {@code problemType}.
 */
@WebMvcTest(HabilitacionController.class)
@Import({OfferingApiSliceSecurityConfig.class, OfferingApiActor.class, OfferingProblemHandler.class})
@DisplayName("HabilitacionController")
class HabilitacionControllerTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 3L;
	private static final long CUENTA = 99L;
	private static final String RUTA = "/api/v1/consultorios/3/ofertas/77";

	@Autowired private MockMvc mockMvc;

	@MockitoBean private OfertaHabilitacionService habilitacionService;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@BeforeEach
	void setUp() {
		given(tenantContextHolder.current()).willReturn(Optional.of(new RequestTenantContext(
				CUENTA, ORG_ID, CONSULTORIO_ID, "ORG_ADMIN", TenantOperationalStatus.ACTIVA)));
	}

	@Test
	@DisplayName("leer publica las banderas de restriccion y quien limita la capacidad")
	void leer() throws Exception {
		given(habilitacionService.leer(any(), eq(ORG_ID), eq(CONSULTORIO_ID), eq(77L)))
				.willReturn(vista());

		mockMvc.perform(get(RUTA + "/habilitaciones").with(miembro()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.restringidaPorProfesional").value(true))
				.andExpect(jsonPath("$.restringidaPorEspacio").value(true))
				.andExpect(jsonPath("$.capacidadEfectiva").value(4))
				.andExpect(jsonPath("$.espacioQueLimita").value("Box 2"))
				.andExpect(jsonPath("$.profesionales[0].membershipId").value(215))
				.andExpect(jsonPath("$.espacios[0].enServicio").value(true));
	}

	@Test
	@DisplayName("profesionales: la lista llega como conjunto, sin repetidos ni nulos")
	void reemplazar_profesionales_normaliza() throws Exception {
		given(habilitacionService.reemplazarProfesionales(
				any(), anyLong(), anyLong(), anyLong(), any(), anyLong())).willReturn(vista());

		mockMvc.perform(put(RUTA + "/habilitaciones/profesionales").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ids\":[215,215,null,216],\"expectedVersion\":4}"))
				.andExpect(status().isOk());

		verify(habilitacionService).reemplazarProfesionales(
				any(), eq(ORG_ID), eq(CONSULTORIO_ID), eq(77L), eq(Set.of(215L, 216L)), eq(4L));
	}

	@Test
	@DisplayName("espacios: lista vacia es quitar la restriccion, no un error")
	void reemplazar_espacios_vacio() throws Exception {
		given(habilitacionService.reemplazarEspacios(
				any(), anyLong(), anyLong(), anyLong(), any(), anyLong())).willReturn(vista());

		mockMvc.perform(put(RUTA + "/habilitaciones/espacios").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ids\":[],\"expectedVersion\":4}"))
				.andExpect(status().isOk());

		verify(habilitacionService).reemplazarEspacios(
				any(), eq(ORG_ID), eq(CONSULTORIO_ID), eq(77L), eq(Set.of()), eq(4L));
	}

	@Test
	@DisplayName("sin la lista es 400: omitirla no es lo mismo que mandarla vacia")
	void sin_lista() throws Exception {
		mockMvc.perform(put(RUTA + "/habilitaciones/espacios").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":4}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(habilitacionService);
	}

	@Test
	@DisplayName("profesional de otra sede: 404 not-found; oferta dada de baja: 409 oferta-inactiva")
	void errores_del_dominio() throws Exception {
		given(habilitacionService.reemplazarProfesionales(
				any(), anyLong(), anyLong(), anyLong(), any(), anyLong()))
				.willThrow(new HabilitacionNoAccesibleException(300L, "profesional"))
				.willThrow(new OfertaInactivaException(77L, "configurar"));
		String cuerpo = "{\"ids\":[300],\"expectedVersion\":4}";

		mockMvc.perform(put(RUTA + "/habilitaciones/profesionales").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value(endsWith("not-found")))
				.andExpect(jsonPath("$.detail").value(
						"Ese profesional no existe en este centro, o no atiende en esta sede."));
		mockMvc.perform(put(RUTA + "/habilitaciones/profesionales").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(endsWith("oferta-inactiva")));
	}

	@Test
	@DisplayName("validar: parametros omitidos viajan null y se devuelven TODOS los motivos")
	void validar() throws Exception {
		given(habilitacionService.validar(any(), anyLong(), anyLong(), anyLong(), any(), any()))
				.willReturn(new ValidacionDeOfertaView(77L, 215L, null, false, 4, List.of(
						new ValidacionDeOfertaView.MotivoDeRechazo(
								"profesional-no-habilitado", "No esta habilitado"),
						new ValidacionDeOfertaView.MotivoDeRechazo(
								"vinculo-no-vigente", "Se desvinculo"))));

		mockMvc.perform(get(RUTA + "/validacion?membershipId=215").with(miembro()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.puedePrestarse").value(false))
				.andExpect(jsonPath("$.motivos.length()").value(2))
				.andExpect(jsonPath("$.motivos[1].codigo").value("vinculo-no-vigente"));

		verify(habilitacionService).validar(
				any(), eq(ORG_ID), eq(CONSULTORIO_ID), eq(77L), eq(215L), isNull());
	}

	@Test
	@DisplayName("sin contexto de trabajo elegido no se llega al servicio")
	void sin_contexto() throws Exception {
		given(tenantContextHolder.current()).willReturn(Optional.empty());

		mockMvc.perform(get(RUTA + "/habilitaciones").with(miembro()))
				.andExpect(status().isForbidden());

		verifyNoInteractions(habilitacionService);
	}

	// =================================================================================
	// Helpers
	// =================================================================================

	private static RequestPostProcessor miembro() {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(CUENTA), "n/a", List.of()));
	}

	private static HabilitacionesView vista() {
		Instant desde = Instant.parse("2026-01-01T00:00:00Z");
		return new HabilitacionesView(77L, 4L, true, true,
				List.of(new HabilitacionesView.ProfesionalHabilitadoView(1L, 215L, "Ana Prueba",
						"PROFESIONAL", desde, null, "ACTIVO", true, true, null, null, 0L)),
				List.of(new HabilitacionesView.EspacioHabilitadoView(2L, 40L, "Box 2", "BOX", 4,
						desde, null, "ACTIVO", true, true, null, null, 0L)),
				6, 4, "Box 2");
	}

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
