package com.akine.activity.api;

import com.akine.activity.application.AjusteDePaseService;
import com.akine.activity.application.MovimientoPaseView;
import com.akine.activity.application.PaseDetalleView;
import com.akine.activity.application.PaseView;
import com.akine.activity.application.ProductoService;
import com.akine.activity.application.VentaDePaseService;
import com.akine.activity.domain.exception.PaseNoAjustableException;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo que agrego AKINE-08.08 a la capa HTTP de pases: el ledger paginado (recorte de {@code page} y
 * {@code size}, forma de la pagina) y la configuracion de la renovacion (cuerpo, 409 con su causa).
 * Las reglas las cubren el servicio y {@code AbonoRenovacionYAvisosIT}.
 */
@WebMvcTest(PaseController.class)
@Import({ActivityApiSliceSecurityConfig.class, ActivityApiActor.class})
@DisplayName("PaseController — ledger paginado y renovacion")
class PaseControllerTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 10L;
	private static final long CUENTA = 99L;
	private static final String PASE = "/api/v1/consultorios/10/pases/77";

	@Autowired private MockMvc mockMvc;

	@MockitoBean private ProductoService productoService;
	@MockitoBean private VentaDePaseService ventaService;
	@MockitoBean private AjusteDePaseService ajusteService;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@BeforeEach
	void setUp() {
		given(tenantContextHolder.current()).willReturn(Optional.of(new RequestTenantContext(
				CUENTA, ORG_ID, SEDE_ID, "ORG_ADMIN", TenantOperationalStatus.ACTIVA)));
	}

	@Test
	@DisplayName("El ledger paginado acota page y size y publica la forma comun de pagina")
	void ledger_paginado() throws Exception {
		MovimientoPaseView compra = new MovimientoPaseView(1L, "COMPRA", 8, 8, "VENTA", 77L, null, CUENTA,
				null, Instant.parse("2026-10-01T12:00:00Z"));
		given(ventaService.movimientos(any(), eq(SEDE_ID), eq(77L), any(), eq(0), eq(100)))
				.willReturn(new PageImpl<>(List.of(compra), PageRequest.of(0, 100), 1));

		autenticado(get(PASE + "/movimientos").param("page", "-3").param("size", "5000"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].tipo").value("COMPRA"))
				.andExpect(jsonPath("$.content[0].saldoResultante").value(8))
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(100))
				.andExpect(jsonPath("$.totalElements").value(1))
				.andExpect(jsonPath("$.totalPages").value(1));
	}

	@Test
	@DisplayName("La renovacion viaja con activa y version, y responde el abono")
	void configurar_renovacion() throws Exception {
		given(ventaService.configurarRenovacion(any(), eq(SEDE_ID), eq(77L), eq(true), eq(3L)))
				.willReturn(abono(true, 4L));

		autenticado(put(PASE + "/renovacion").contentType(MediaType.APPLICATION_JSON)
				.content("{\"activa\": true, \"version\": 3}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.renovacionAutomatica").value(true))
				.andExpect(jsonPath("$.renovacionAutorizadaPor").value("Ana Gomez"))
				.andExpect(jsonPath("$.version").value(4));
	}

	@Test
	@DisplayName("sinMovimientos=true llega al servicio como sin ledger; sin el parametro, con el ledger")
	void ajuste_sin_movimientos() throws Exception {
		given(ajusteService.ajustar(any(), eq(SEDE_ID), eq(77L), any(), anyBoolean()))
				.willReturn(new PaseDetalleView(abono(false, 2L), List.of()));
		String cuerpo = "{\"cantidad\": -1, \"motivo\": \"Correccion\", \"version\": 1}";

		autenticado(post(PASE + "/ajustes").param("sinMovimientos", "true")
				.contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pase.version").value(2))
				.andExpect(jsonPath("$.movimientos").isEmpty());
		verify(ajusteService).ajustar(any(), eq(SEDE_ID), eq(77L), any(), eq(false));

		autenticado(post(PASE + "/ajustes").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andExpect(status().isOk());
		verify(ajusteService).ajustar(any(), eq(SEDE_ID), eq(77L), any(), eq(true));
	}

	@Test
	@DisplayName("Sin activa es 400 y el servicio no se llama")
	void renovacion_sin_activa_es_400() throws Exception {
		autenticado(put(PASE + "/renovacion").contentType(MediaType.APPLICATION_JSON)
				.content("{\"version\": 3}"))
				.andExpect(status().isBadRequest());

		verify(ventaService, never()).configurarRenovacion(any(), anyLong(), anyLong(), anyBoolean(), anyLong());
	}

	@Test
	@DisplayName("Un abono ya renovado es 409 pase-no-ajustable con causa YA_RENOVADO")
	void ya_renovado_es_409() throws Exception {
		given(ventaService.configurarRenovacion(any(), anyLong(), anyLong(), anyBoolean(), anyLong()))
				.willThrow(new PaseNoAjustableException(77L, PaseNoAjustableException.Causa.YA_RENOVADO,
						"ya se renovo"));

		autenticado(put(PASE + "/renovacion").contentType(MediaType.APPLICATION_JSON)
				.content("{\"activa\": true, \"version\": 3}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("pase-no-ajustable")))
				.andExpect(jsonPath("$.causa").value("YA_RENOVADO"));
	}

	// =================================================================================

	private static PaseView abono(boolean renovacion, long version) {
		return new PaseView(77L, SEDE_ID, 512L, 12L, "ABONO", "Abono mensual", new BigDecimal("30000.00"),
				"ARS", 42L, 8, 8, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"), "ACTIVO",
				true, 0, true, Instant.parse("2026-10-30T12:00:00Z"), CUENTA, null, null, null,
				renovacion, null, null, renovacion ? "Ana Gomez" : null, null, version);
	}

	private ResultActions autenticado(MockHttpServletRequestBuilder peticion) throws Exception {
		return mockMvc.perform(peticion.with(authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(CUENTA), "n/a", List.of()))));
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
			return SEDE_ID;
		}
	}
}
