package com.akine.person.api;

import com.akine.person.api.dto.CreateAutorizacionRequest;
import com.akine.person.api.dto.DeactivateDocumentoRequest;
import com.akine.person.api.dto.RevertirConsumoRequest;
import com.akine.person.api.dto.UpdateAutorizacionRequest;
import com.akine.person.api.dto.VincularDocumentoRequest;
import com.akine.person.application.AutorizacionService;
import com.akine.person.application.CoberturaParaOfertaService;
import com.akine.person.application.ConsumoDeAutorizacionService;
import com.akine.person.application.ElegibilidadAdministrativaService;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Capa REST de autorizaciones, su consumo, la elegibilidad administrativa y la cobertura aplicable
 * a una oferta (M17, B-3, C-4). Las órdenes, coberturas, adjuntos y personas tienen sus propios
 * slices; éste recorre lo que ellos no, y el advice entero del módulo.
 */
@WebMvcTest({AutorizacionController.class, ConsumoDeAutorizacionController.class,
		CoberturaParaOfertaController.class, ElegibilidadController.class})
@Import({SliceDeController.Seguridad.class, PersonApiActor.class,
		PersonControllersTest.Servicios.class})
class PersonControllersTest extends SliceRestDeModulo {

	@Autowired private AutorizacionService autorizaciones;
	@Autowired private ConsumoDeAutorizacionService consumos;
	@Autowired private CoberturaParaOfertaService coberturaParaOferta;
	@Autowired private ElegibilidadAdministrativaService elegibilidad;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@Override
	protected String prefijo() {
		return "/api/v1";
	}

	@Override
	protected Object[] servicios() {
		return new Object[] {autorizaciones, consumos, coberturaParaOferta, elegibilidad};
	}

	@Override
	protected void provocar(Throwable excepcion) {
		given(autorizaciones.ver(any(), anyLong(), anyLong(), any())).willThrow(excepcion);
	}

	@Override
	protected MockHttpServletRequestBuilder pedidoQueFalla() {
		return get("/api/v1/personas/12/autorizaciones/40");
	}

	static Stream<Arguments> operaciones() {
		String aut = "/personas/12/autorizaciones";
		return Stream.of(
				op("GET", aut + "?fecha=2026-10-01", null, 200),
				op("GET", aut + "/40", null, 200),
				op("GET", aut + "/40/historial", null, 200),
				op("GET", aut + "/elegibles", null, 200),
				op("POST", aut, "{\"coberturaId\":3,\"practicaId\":5,\"numero\":\"A-1\","
						+ "\"cantidadAutorizada\":10,\"vigenciaDesde\":\"2026-10-01\","
						+ "\"vigenciaHasta\":\"2026-12-31\"}", 201),
				op("PUT", aut + "/40", UpdateAutorizacionRequest.class, 200),
				op("POST", aut + "/40/estado", "{\"accion\":\"APROBAR\",\"cantidadAutorizada\":10,"
						+ "\"expectedVersion\":2}", 200),
				op("POST", aut + "/40/documento", VincularDocumentoRequest.class, 200),
				op("DELETE", aut + "/40", DeactivateDocumentoRequest.class, 204),
				op("GET", "/autorizaciones/40/saldo?fecha=2026-10-01", null, 200),
				op("GET", "/autorizaciones/40/movimientos", null, 200),
				op("GET", "/autorizaciones/40/alertas", null, 200),
				op("POST", "/autorizaciones/40/reversiones", RevertirConsumoRequest.class, 201),
				op("GET", "/personas/12/cobertura-aplicable?ofertaId=4", null, 200),
				op("GET", "/personas/12/elegibilidad?coberturaId=3&practicaId=5", null, 200));
	}

	static Stream<Class<? extends Throwable>> excepciones() {
		return SliceDeController.excepcionesManejadas(PersonProblemHandler.class);
	}

	@Test
	@DisplayName("el saldo a una fecha pasa la fecha al servicio")
	void saldo_a_fecha() throws Exception {
		mockMvc.perform(get("/api/v1/autorizaciones/40/saldo").with(miembro())
						.param("fecha", "2026-11-15"))
				.andExpect(status().isOk());

		verify(consumos).saldo(any(), eq(40L), eq(LocalDate.of(2026, 11, 15)));
	}

	@Test
	@DisplayName("revertir un consumo sin motivo es 400 y no llega al servicio")
	void reversion_sin_motivo() throws Exception {
		mockMvc.perform(post("/api/v1/autorizaciones/40/reversiones").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"movimientoId\":7,\"motivo\":\" \"}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(consumos);
	}

	@Test
	@DisplayName("una autorizacion con estado inicial desconocido es 400, no 500")
	void estado_inicial_desconocido() throws Exception {
		mockMvc.perform(post("/api/v1/personas/12/autorizaciones").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content(cuerpo(CreateAutorizacionRequest.class)))
				.andExpect(status().is4xxClientError());

		verifyNoInteractions(autorizaciones);
	}

	@TestConfiguration
	static class Servicios {

		@Bean AutorizacionService autorizacionService() {
			return Fixture.mockQueResponde(AutorizacionService.class);
		}

		@Bean ConsumoDeAutorizacionService consumoDeAutorizacionService() {
			return Fixture.mockQueResponde(ConsumoDeAutorizacionService.class);
		}

		@Bean CoberturaParaOfertaService coberturaParaOfertaService() {
			return Fixture.mockQueResponde(CoberturaParaOfertaService.class);
		}

		@Bean ElegibilidadAdministrativaService elegibilidadAdministrativaService() {
			return Fixture.mockQueResponde(ElegibilidadAdministrativaService.class);
		}
	}
}
