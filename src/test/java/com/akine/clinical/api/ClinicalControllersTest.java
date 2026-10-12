package com.akine.clinical.api;

import com.akine.clinical.api.dto.ActualizarResumenRequest;
import com.akine.clinical.api.dto.BajaDeAdjuntoClinicoRequest;
import com.akine.clinical.api.dto.BajaDeAntecedenteRequest;
import com.akine.clinical.api.dto.BajaDeEntradaClinicaRequest;
import com.akine.clinical.api.dto.CerrarCasoClinicoRequest;
import com.akine.clinical.api.dto.CrearPlanTratamientoRequest;
import com.akine.clinical.api.dto.EditarCasoClinicoRequest;
import com.akine.clinical.api.dto.EnmendarEntradaClinicaRequest;
import com.akine.clinical.api.dto.FinalizarPlanTratamientoRequest;
import com.akine.clinical.api.dto.ModificarPlanTratamientoRequest;
import com.akine.clinical.api.dto.ReabrirCasoClinicoRequest;
import com.akine.clinical.api.dto.ReclasificarAdjuntoClinicoRequest;
import com.akine.clinical.api.dto.RegistrarAntecedenteRequest;
import com.akine.clinical.api.dto.RegistrarEntradaClinicaRequest;
import com.akine.clinical.api.dto.SuspenderPlanTratamientoRequest;
import com.akine.clinical.api.dto.TransicionDePlanRequest;
import com.akine.clinical.api.dto.VincularAutorizacionRequest;
import com.akine.clinical.application.AdjuntoClinicoService;
import com.akine.clinical.application.AntecedenteClinicoService;
import com.akine.clinical.application.CasoClinicoService;
import com.akine.clinical.application.ContenidoDeAdjuntoClinico;
import com.akine.clinical.application.EntradaClinicaService;
import com.akine.clinical.application.HistoriaClinicaService;
import com.akine.clinical.application.PlanTratamientoService;
import com.akine.clinical.application.TimelineService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static com.akine.testsupport.SliceDeController.miembro;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Capa REST de {@code clinical} (M09–M11, M25): historia, entradas, casos, planes, adjuntos y
 * timeline. Todo pedido lleva {@code X-Justificacion-Acceso}, y lo que se verifica además del
 * status es que esa cabecera llegue al servicio —es la justificación que se audita— y nunca por
 * query string.
 */
@WebMvcTest({AdjuntoClinicoController.class, CasoClinicoController.class,
		EntradaClinicaController.class, HistoriaClinicaController.class,
		PlanTratamientoController.class, TimelineController.class})
@Import({SliceDeController.Seguridad.class, ClinicalApiActor.class,
		ClinicalControllersTest.Servicios.class})
class ClinicalControllersTest extends SliceRestDeModulo {

	private static final String MOTIVO = "Control solicitado por el paciente";

	@Autowired private AdjuntoClinicoService adjuntos;
	@Autowired private AntecedenteClinicoService antecedentes;
	@Autowired private CasoClinicoService casos;
	@Autowired private EntradaClinicaService entradas;
	@Autowired private HistoriaClinicaService historias;
	@Autowired private PlanTratamientoService planes;
	@Autowired private TimelineService timeline;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@Override
	protected String prefijo() {
		return "/api/v1";
	}

	@Override
	protected Object[] servicios() {
		return new Object[] {adjuntos, antecedentes, casos, entradas, historias, planes, timeline};
	}

	@Override
	protected MockHttpServletRequestBuilder decorar(MockHttpServletRequestBuilder pedido) {
		return pedido.header("X-Justificacion-Acceso", MOTIVO);
	}

	@Override
	protected boolean exigeLocation(String ruta) {
		// Los antecedentes no tienen GET individual: el 201 no manda Location.
		return !ruta.endsWith("/antecedentes");
	}

	@Override
	protected void provocar(Throwable excepcion) {
		given(historias.ver(any(), anyLong(), any())).willThrow(excepcion);
	}

	@Override
	protected MockHttpServletRequestBuilder pedidoQueFalla() {
		return get("/api/v1/historias-clinicas/por-persona/12");
	}

	static Stream<Arguments> operaciones() {
		String hc = "/historias-clinicas/88";
		String persona = "/historias-clinicas/por-persona/12";
		String caso = "/casos-clinicos/5";
		String plan = "/planes-tratamiento/6";
		String entrada = "/entradas-clinicas/9";
		return Stream.of(
				// historia por persona y antecedentes
				op("PUT", persona, null, 200),
				op("GET", persona, null, 200),
				op("PUT", persona + "/resumen", ActualizarResumenRequest.class, 200),
				op("GET", persona + "/antecedentes?tipo=MEDICO", null, 200),
				op("POST", persona + "/antecedentes", RegistrarAntecedenteRequest.class, 201),
				op("POST", persona + "/antecedentes/3/baja", BajaDeAntecedenteRequest.class, 200),
				// entradas
				op("POST", hc + "/entradas", RegistrarEntradaClinicaRequest.class, 201),
				op("GET", hc + "/entradas", null, 200),
				op("GET", entrada, null, 200),
				op("GET", entrada + "/versiones", null, 200),
				op("POST", entrada + "/enmiendas", EnmendarEntradaClinicaRequest.class, 200),
				op("DELETE", entrada, BajaDeEntradaClinicaRequest.class, 200),
				// casos
				op("POST", hc + "/casos", "{\"ofertaId\":4,\"diagnosticoPresuntivo\":\"Lumbalgia\","
						+ "\"equipo\":[{\"profesionalMembershipId\":3,\"rol\":\"TRATANTE\"}]}", 201),
				op("GET", hc + "/casos?soloActivos=true", null, 200),
				op("GET", caso, null, 200),
				op("PATCH", caso, EditarCasoClinicoRequest.class, 200),
				op("POST", caso + "/cierre", CerrarCasoClinicoRequest.class, 200),
				op("POST", caso + "/reapertura", ReabrirCasoClinicoRequest.class, 200),
				op("PUT", caso + "/equipo", "{\"integrantes\":[{\"profesionalMembershipId\":3,"
						+ "\"rol\":\"RESPONSABLE\"}],\"expectedVersion\":1}", 200),
				op("GET", caso + "/eventos", null, 200),
				// planes
				op("POST", caso + "/planes", CrearPlanTratamientoRequest.class, 201),
				op("GET", caso + "/planes", null, 200),
				op("GET", plan, null, 200),
				op("PATCH", plan, ModificarPlanTratamientoRequest.class, 200),
				op("POST", plan + "/activacion", TransicionDePlanRequest.class, 200),
				op("POST", plan + "/autorizacion", VincularAutorizacionRequest.class, 200),
				op("POST", plan + "/suspension", SuspenderPlanTratamientoRequest.class, 200),
				op("POST", plan + "/reanudacion", TransicionDePlanRequest.class, 200),
				op("POST", plan + "/finalizacion", FinalizarPlanTratamientoRequest.class, 200),
				op("GET", plan + "/versiones", null, 200),
				op("GET", plan + "/avance?numeroVersion=2", null, 200),
				// adjuntos y timeline
				op("GET", hc + "/adjuntos?categoria=ESTUDIO", null, 200),
				op("PATCH", hc + "/adjuntos/17", ReclasificarAdjuntoClinicoRequest.class, 200),
				op("DELETE", hc + "/adjuntos/17", BajaDeAdjuntoClinicoRequest.class, 200),
				op("GET", hc + "/timeline?limite=10&casoId=5", null, 200));
	}

	static Stream<Class<? extends Throwable>> excepciones() {
		return SliceDeController.excepcionesManejadas(ClinicalProblemHandler.class);
	}

	@Test
	@DisplayName("la justificacion viaja por cabecera hasta el servicio; sin cabecera llega null")
	void justificacion_por_cabecera() throws Exception {
		mockMvc.perform(decorar(get("/api/v1/historias-clinicas/por-persona/12")).with(miembro()))
				.andExpect(status().isOk());
		mockMvc.perform(get("/api/v1/historias-clinicas/por-persona/12")
						.param("justificacion", "por query no vale").with(miembro()))
				.andExpect(status().isOk());

		verify(historias).ver(any(), eq(12L), eq(MOTIVO));
		verify(historias).ver(any(), eq(12L), isNull());
	}

	@Test
	@DisplayName("subir un adjunto es multipart, 201 con Location anidado; la descarga es attachment y nosniff")
	void adjunto_subida_y_descarga() throws Exception {
		given(adjuntos.contenido(any(), anyLong(), anyLong(), any())).willReturn(
				new ContenidoDeAdjuntoClinico("estudio.pdf", "application/pdf",
						"%PDF-1.7".getBytes(StandardCharsets.US_ASCII)));

		mockMvc.perform(multipart("/api/v1/historias-clinicas/88/adjuntos")
						.file(new MockMultipartFile("archivo", "estudio.pdf", "application/pdf",
								"%PDF-1.7".getBytes(StandardCharsets.US_ASCII)))
						.param("categoria", "ESTUDIO")
						.param("titulo", "Resonancia de rodilla")
						.header("X-Justificacion-Acceso", MOTIVO)
						.with(miembro()))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/historias-clinicas/88/adjuntos/1"));

		mockMvc.perform(decorar(get("/api/v1/historias-clinicas/88/adjuntos/17/contenido"))
						.with(miembro()))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Content-Type-Options", "nosniff"))
				.andExpect(header().string("Content-Disposition",
						org.hamcrest.Matchers.startsWith("attachment")))
				.andExpect(header().string("Content-Type", "application/pdf"));

		verify(adjuntos).contenido(any(), eq(88L), eq(17L), eq(MOTIVO));
	}

	@TestConfiguration
	static class Servicios {

		@Bean AdjuntoClinicoService adjuntoClinicoService() {
			return Fixture.mockQueResponde(AdjuntoClinicoService.class);
		}

		@Bean AntecedenteClinicoService antecedenteClinicoService() {
			return Fixture.mockQueResponde(AntecedenteClinicoService.class);
		}

		@Bean CasoClinicoService casoClinicoService() {
			return Fixture.mockQueResponde(CasoClinicoService.class);
		}

		@Bean EntradaClinicaService entradaClinicaService() {
			return Fixture.mockQueResponde(EntradaClinicaService.class);
		}

		@Bean HistoriaClinicaService historiaClinicaService() {
			return Fixture.mockQueResponde(HistoriaClinicaService.class);
		}

		@Bean PlanTratamientoService planTratamientoService() {
			return Fixture.mockQueResponde(PlanTratamientoService.class);
		}

		@Bean TimelineService timelineService() {
			return Fixture.mockQueResponde(TimelineService.class);
		}
	}
}
