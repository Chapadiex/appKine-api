package com.akine.billing.api;

import com.akine.billing.api.dto.AbrirCajaRequest;
import com.akine.billing.api.dto.AgregarItemRequest;
import com.akine.billing.api.dto.AnularCobroRequest;
import com.akine.billing.api.dto.AnularEgresoRequest;
import com.akine.billing.api.dto.EditarEgresoRequest;
import com.akine.billing.api.dto.AnularObligacionRequest;
import com.akine.billing.api.dto.AnularPresentacionRequest;
import com.akine.billing.api.dto.CerrarCajaRequest;
import com.akine.billing.api.dto.CrearPresentacionRequest;
import com.akine.billing.api.dto.ImputarSaldoAFavorRequest;
import com.akine.billing.api.dto.RegistrarCobroRequest;
import com.akine.billing.api.dto.RegistrarDebitoRequest;
import com.akine.billing.api.dto.RegistrarEgresoRequest;
import com.akine.billing.api.dto.RegistrarFacturaRequest;
import com.akine.billing.api.dto.RegistrarMovimientoRequest;
import com.akine.billing.api.dto.RegistrarPagoEgresoRequest;
import com.akine.billing.api.dto.RegistrarPagoFinanciadorRequest;
import com.akine.billing.api.dto.ReintegrarSaldoAFavorRequest;
import com.akine.billing.api.dto.RevertirMovimientoRequest;
import com.akine.billing.application.CajaService;
import com.akine.billing.application.CobroPosteriorService;
import com.akine.billing.application.CobroService;
import com.akine.billing.application.EgresoService;
import com.akine.billing.application.FinanciadorPagoService;
import com.akine.billing.application.MovimientoCajaService;
import com.akine.billing.application.ObligacionService;
import com.akine.billing.application.PagoEgresoService;
import com.akine.billing.application.PresentacionCsv;
import com.akine.billing.application.PresentacionService;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.testsupport.Fixture;
import com.akine.testsupport.SliceDeController;
import com.akine.testsupport.SliceRestDeModulo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.util.stream.Stream;

import static com.akine.testsupport.SliceDeController.miembro;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Capa REST de {@code billing} (M18–M22): obligaciones, cobros, caja, egresos y presentaciones.
 *
 * <p>Los servicios son mocks que devuelven vistas completas ({@link Fixture#mockQueResponde}), así
 * que cada endpoint recorre su mapeo vista → DTO entero. Lo que se afirma es el borde HTTP: el
 * status de cada operación (201 con {@code Location} contra 200 contra 204), que los filtros y el
 * cuerpo lleguen al servicio, que un cuerpo inválido muera en la validación, y que TODA excepción
 * que el advice del módulo declara salga como Problem Detail tipado con su status propio.
 */
@WebMvcTest({CajaController.class, CobroController.class, EgresoController.class,
		ObligacionController.class, PresentacionController.class})
@Import({SliceDeController.Seguridad.class, BillingApiActor.class, PresentacionCsv.class,
		BillingControllersTest.Servicios.class})
class BillingControllersTest extends SliceRestDeModulo {

	private static final String SEDE = "/api/v1/consultorios/7";

	@Autowired private CajaService caja;
	@Autowired private MovimientoCajaService movimientos;
	@Autowired private CobroService cobros;
	@Autowired private CobroPosteriorService cobrosPosteriores;
	@Autowired private EgresoService egresos;
	@Autowired private PagoEgresoService pagosEgreso;
	@Autowired private ObligacionService obligaciones;
	@Autowired private PresentacionService presentaciones;
	@Autowired private FinanciadorPagoService pagosFinanciador;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@Override
	protected String prefijo() {
		return SEDE;
	}

	@Override
	protected Object[] servicios() {
		return new Object[] {caja, movimientos, cobros, cobrosPosteriores, egresos, pagosEgreso,
				obligaciones, presentaciones, pagosFinanciador};
	}

	@Override
	protected void provocar(Throwable excepcion) {
		given(obligaciones.ver(any(), anyLong(), anyLong())).willThrow(excepcion);
	}

	@Override
	protected MockHttpServletRequestBuilder pedidoQueFalla() {
		return get(SEDE + "/obligaciones/9");
	}

	static Stream<Arguments> operaciones() {
		return Stream.of(
				// caja
				op("POST", "/caja/jornadas", AbrirCajaRequest.class, 201),
				op("POST", "/caja/jornadas/3/cierre", CerrarCajaRequest.class, 200),
				op("GET", "/caja/jornadas/3", null, 200),
				op("GET", "/caja/jornadas?estado=ABIERTA&desde=2026-10-01", null, 200),
				op("POST", "/caja/movimientos", RegistrarMovimientoRequest.class, 201),
				op("POST", "/caja/movimientos/4/reversion", RevertirMovimientoRequest.class, 201),
				op("GET", "/caja/movimientos?jornadaId=3", null, 200),
				// cobros
				op("POST", "/cobros", RegistrarCobroRequest.class, 201),
				op("POST", "/cobros/5/imputaciones", ImputarSaldoAFavorRequest.class, 200),
				op("POST", "/cobros/5/anulacion", AnularCobroRequest.class, 200),
				op("POST", "/cobros/5/reintegros", ReintegrarSaldoAFavorRequest.class, 201),
				op("GET", "/cobros/5", null, 200),
				op("GET", "/cobros?personaId=128", null, 200),
				// egresos
				op("POST", "/egresos", RegistrarEgresoRequest.class, 201),
				op("PUT", "/egresos/6", EditarEgresoRequest.class, 200),
				op("POST", "/egresos/6/confirmacion", null, 200),
				op("POST", "/egresos/6/anulacion", AnularEgresoRequest.class, 200),
				op("GET", "/egresos/6", null, 200),
				op("GET", "/egresos?estado=CONFIRMADO", null, 200),
				op("POST", "/egresos/6/pagos", RegistrarPagoEgresoRequest.class, 201),
				op("POST", "/egresos/6/pagos/2/anulacion", AnularEgresoRequest.class, 200),
				// obligaciones
				op("GET", "/obligaciones?personaId=128", null, 200),
				op("GET", "/obligaciones/9", null, 200),
				op("DELETE", "/obligaciones/9", AnularObligacionRequest.class, 200),
				// presentaciones
				op("GET", "/prestaciones-elegibles?financiadorId=3", null, 200),
				op("POST", "/presentaciones", CrearPresentacionRequest.class, 201),
				op("POST", "/presentaciones/11/items", AgregarItemRequest.class, 201),
				op("DELETE", "/presentaciones/11/items/12", null, 204),
				op("GET", "/presentaciones/11/validacion", null, 200),
				op("POST", "/presentaciones/11/confirmacion", null, 200),
				op("POST", "/presentaciones/11/factura", RegistrarFacturaRequest.class, 200),
				op("POST", "/presentaciones/11/items/12/debito", RegistrarDebitoRequest.class, 200),
				op("POST", "/presentaciones/11/pagos", RegistrarPagoFinanciadorRequest.class, 201),
				op("GET", "/presentaciones/11/pagos", null, 200),
				op("POST", "/presentaciones/11/conciliacion", null, 200),
				op("POST", "/presentaciones/11/anulacion", AnularPresentacionRequest.class, 200),
				op("GET", "/presentaciones?financiadorId=3", null, 200),
				op("GET", "/presentaciones/11", null, 200),
				op("GET", "/presentaciones/11/export", null, 200),
				op("GET", "/financiadores/3/cuenta-corriente", null, 200));
	}

	@Test
	@DisplayName("un cobro creado responde 201 con Location al recurso y el id de la vista")
	void cobro_location() throws Exception {
		mockMvc.perform(post(SEDE + "/cobros").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content(json.writeValueAsString(Fixture.de(RegistrarCobroRequest.class))))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", SEDE + "/cobros/1"))
				.andExpect(jsonPath("$.id").value(1));
	}

	@Test
	@DisplayName("los filtros y la paginacion de la bandeja de presentaciones llegan al servicio")
	void filtros_de_presentaciones() throws Exception {
		mockMvc.perform(get(SEDE + "/presentaciones").with(miembro())
						.param("estado", "CONFIRMADA")
						.param("financiadorId", "3")
						.param("desde", "2026-09-01")
						.param("limite", "10")
						.param("desplazamiento", "20"))
				.andExpect(status().isOk());
		mockMvc.perform(get(SEDE + "/prestaciones-elegibles").with(miembro())
						.param("financiadorId", "3"))
				.andExpect(status().isOk());

		verify(presentaciones).buscar(any(), eq(7L), eq("CONFIRMADA"), eq(3L),
				eq(LocalDate.of(2026, 9, 1)), isNull(), isNull(), eq(10), eq(20));
		verify(presentaciones).elegibles(any(), eq(7L), eq(3L), isNull(), isNull(), eq(50), eq(0));
	}

	@Test
	@DisplayName("el export de un lote es un CSV adjunto que serializa el mismo detalle")
	void export_de_presentacion() throws Exception {
		mockMvc.perform(get(SEDE + "/presentaciones/11/export").with(miembro()))
				.andExpect(status().isOk())
				.andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
				.andExpect(header().string("Content-Disposition",
						org.hamcrest.Matchers.startsWith("attachment; filename=\"presentacion-")));

		verify(presentaciones).detalle(any(), eq(7L), eq(11L));
	}

	@Test
	@DisplayName("un cobro sin medios es 400 y no llega al servicio")
	void cobro_invalido() throws Exception {
		mockMvc.perform(post(SEDE + "/cobros").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"personaId\":128,\"total\":100,\"medios\":[]}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(cobros);
	}

	static Stream<Class<? extends Throwable>> excepciones() {
		return SliceDeController.excepcionesManejadas(BillingProblemHandler.class);
	}

	@TestConfiguration
	static class Servicios {

		@Bean CajaService cajaService() {
			return Fixture.mockQueResponde(CajaService.class);
		}

		@Bean MovimientoCajaService movimientoCajaService() {
			return Fixture.mockQueResponde(MovimientoCajaService.class);
		}

		@Bean CobroService cobroService() {
			return Fixture.mockQueResponde(CobroService.class);
		}

		@Bean CobroPosteriorService cobroPosteriorService() {
			return Fixture.mockQueResponde(CobroPosteriorService.class);
		}

		@Bean EgresoService egresoService() {
			return Fixture.mockQueResponde(EgresoService.class);
		}

		@Bean PagoEgresoService pagoEgresoService() {
			return Fixture.mockQueResponde(PagoEgresoService.class);
		}

		@Bean ObligacionService obligacionService() {
			return Fixture.mockQueResponde(ObligacionService.class);
		}

		@Bean PresentacionService presentacionService() {
			return Fixture.mockQueResponde(PresentacionService.class);
		}

		@Bean FinanciadorPagoService financiadorPagoService() {
			return Fixture.mockQueResponde(FinanciadorPagoService.class);
		}
	}
}
