package com.akine.offering.api;

import com.akine.offering.application.OfertaPrecioParticularService;
import com.akine.offering.application.OfertaPrecioParticularView;
import com.akine.offering.domain.exception.PrecioParticularInactivoException;
import com.akine.offering.domain.exception.PrecioParticularNoAccesibleException;
import com.akine.offering.domain.exception.PrecioParticularSolapadoException;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La capa REST de los precios particulares por vigencia de una oferta (RF-M16-009).
 *
 * <p>El 409 de solapamiento es el que la pantalla necesita para explicarse: por eso se fija que
 * viaje con el id y el periodo del precio que choca, no solo con el tipo.
 */
@WebMvcTest(OfertaPrecioParticularController.class)
@Import({OfferingApiSliceSecurityConfig.class, OfferingApiActor.class, OfferingProblemHandler.class})
@DisplayName("OfertaPrecioParticularController")
class OfertaPrecioParticularControllerTest {

	private static final long ORG_ID = 1L;
	private static final long CONSULTORIO_ID = 3L;
	private static final long CUENTA = 99L;
	private static final String RUTA = "/api/v1/consultorios/3/ofertas/77/precios-particulares";

	@Autowired private MockMvc mockMvc;

	@MockitoBean private OfertaPrecioParticularService precioService;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@BeforeEach
	void setUp() {
		given(tenantContextHolder.current()).willReturn(Optional.of(new RequestTenantContext(
				CUENTA, ORG_ID, CONSULTORIO_ID, "ORG_ADMIN", TenantOperationalStatus.ACTIVA)));
	}

	@Test
	@DisplayName("listar publica importe, vigencia y cual rige hoy")
	void listar() throws Exception {
		given(precioService.listar(any(), eq(ORG_ID), eq(CONSULTORIO_ID), eq(77L)))
				.willReturn(List.of(vista()));

		mockMvc.perform(get(RUTA).with(miembro()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].importe").value(9200.00))
				.andExpect(jsonPath("$[0].vigenciaDesde").value("2026-11-01"))
				.andExpect(jsonPath("$[0].vigente").value(true));
	}

	@Test
	@DisplayName("crear: 201 con Location y la moneda omitida viaja null (hereda la de lista)")
	void crear() throws Exception {
		given(precioService.crear(any(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any()))
				.willReturn(vista());

		mockMvc.perform(post(RUTA).with(miembro()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"importe\":9200.00,\"vigenciaDesde\":\"2026-11-01\"}"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", RUTA + "/5"));

		verify(precioService).crear(any(), eq(ORG_ID), eq(CONSULTORIO_ID), eq(77L),
				eq(new BigDecimal("9200.00")), isNull(), eq(LocalDate.of(2026, 11, 1)), isNull());
	}

	@Test
	@DisplayName("importe negativo o moneda que no es ISO: 400 sin tocar el servicio")
	void crear_invalido() throws Exception {
		mockMvc.perform(post(RUTA).with(miembro()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"importe\":-1,\"vigenciaDesde\":\"2026-11-01\"}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post(RUTA).with(miembro()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"importe\":10,\"moneda\":\"PESOS\",\"vigenciaDesde\":\"2026-11-01\"}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(precioService);
	}

	@Test
	@DisplayName("solapado: 409 precio-particular-solapado con el id y el periodo que choca")
	void solapado() throws Exception {
		given(precioService.crear(any(), anyLong(), anyLong(), anyLong(), any(), any(), any(), any()))
				.willThrow(new PrecioParticularSolapadoException(4L, "2026-10-01 a 2026-12-31"));

		mockMvc.perform(post(RUTA).with(miembro()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"importe\":9200.00,\"vigenciaDesde\":\"2026-11-01\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(endsWith("precio-particular-solapado")))
				.andExpect(jsonPath("$.precioExistenteId").value(4))
				.andExpect(jsonPath("$.periodoExistente").value("2026-10-01 a 2026-12-31"));
	}

	@Test
	@DisplayName("cambiar fin: null reabre la vigencia; precio dado de baja: 409 precio-particular-inactivo")
	void cambiar_fin() throws Exception {
		given(precioService.cambiarFin(any(), anyLong(), anyLong(), anyLong(), anyLong(), any(),
				anyLong()))
				.willReturn(vista())
				.willThrow(new PrecioParticularInactivoException(5L));
		String cuerpo = "{\"vigenciaHasta\":null,\"expectedVersion\":2}";

		mockMvc.perform(put(RUTA + "/5").with(miembro()).contentType(MediaType.APPLICATION_JSON)
						.content(cuerpo))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(5));
		mockMvc.perform(put(RUTA + "/5").with(miembro()).contentType(MediaType.APPLICATION_JSON)
						.content(cuerpo))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(endsWith("precio-particular-inactivo")));

		verify(precioService, org.mockito.Mockito.times(2)).cambiarFin(
				any(), eq(ORG_ID), eq(CONSULTORIO_ID), eq(77L), eq(5L), isNull(), eq(2L));
	}

	@Test
	@DisplayName("baja: 204 con Accept problem+json; precio de otra oferta: 404 not-found")
	void baja() throws Exception {
		given(precioService.darDeBaja(any(), anyLong(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(vista())
				.willThrow(new PrecioParticularNoAccesibleException(5L));
		String cuerpo = "{\"reason\":\"Carga equivocada\"}";

		mockMvc.perform(delete(RUTA + "/5").with(miembro())
						.accept(MediaType.APPLICATION_PROBLEM_JSON)
						.contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andExpect(status().isNoContent());
		mockMvc.perform(delete(RUTA + "/5").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value(endsWith("not-found")));
	}

	@Test
	@DisplayName("sin contexto de trabajo elegido no se llega al servicio")
	void sin_contexto() throws Exception {
		given(tenantContextHolder.current()).willReturn(Optional.empty());

		mockMvc.perform(get(RUTA).with(miembro()))
				.andExpect(status().isForbidden());

		verifyNoInteractions(precioService);
	}

	// =================================================================================
	// Helpers
	// =================================================================================

	private static RequestPostProcessor miembro() {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(CUENTA), "n/a", List.of()));
	}

	private static OfertaPrecioParticularView vista() {
		return new OfertaPrecioParticularView(5L, 77L, new BigDecimal("9200.00"), "ARS",
				LocalDate.of(2026, 11, 1), null, true, "ACTIVO", null, null, 2L);
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
