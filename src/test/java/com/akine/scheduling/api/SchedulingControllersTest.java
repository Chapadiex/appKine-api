package com.akine.scheduling.api;

import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.scheduling.api.dto.CancelarSerieDeTurnosRequest;
import com.akine.scheduling.api.dto.CancelarTurnoRequest;
import com.akine.scheduling.api.dto.RegistrarAusenciaRequest;
import com.akine.scheduling.api.dto.ReprogramarSerieDeTurnosRequest;
import com.akine.scheduling.api.dto.ReprogramarTurnoRequest;
import com.akine.scheduling.api.dto.ReservarTurnoRequest;
import com.akine.scheduling.application.AgendaService;
import com.akine.scheduling.application.AgendaUnificadaService;
import com.akine.scheduling.application.CicloDeRecepcionService;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.ResultadoDeReserva;
import com.akine.scheduling.application.SerieDeTurnosService;
import com.akine.scheduling.application.TurnoService;
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

import com.akine.scheduling.domain.exception.OcurrenciaSinLugarException;
import com.akine.scheduling.domain.exception.SlotNoDisponibleException;

import java.time.Instant;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Capa REST de {@code scheduling} (M12, M13): turnos, agenda, agenda unificada y series. La
 * recepción tiene su propio slice ({@code RecepcionControllerTest}).
 */
@WebMvcTest({AgendaController.class, AgendaUnificadaController.class,
		SerieDeTurnosController.class, TurnoController.class})
@Import({SliceDeController.Seguridad.class, SchedulingApiActor.class,
		SchedulingControllersTest.Servicios.class})
class SchedulingControllersTest extends SliceRestDeModulo {

	private static final String SEDE = "/api/v1/consultorios/7";

	@Autowired private AgendaService agenda;
	@Autowired private AgendaUnificadaService agendaUnificada;
	@Autowired private SerieDeTurnosService series;
	@Autowired private TurnoService turnos;
	@Autowired private CicloDeTurnoService ciclo;
	@Autowired private RecepcionService recepcion;
	@Autowired private CicloDeRecepcionService cicloDeRecepcion;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@Override
	protected String prefijo() {
		return SEDE;
	}

	@Override
	protected Object[] servicios() {
		return new Object[] {agenda, agendaUnificada, series, turnos, ciclo, recepcion, cicloDeRecepcion};
	}

	@Override
	protected void provocar(Throwable excepcion) {
		given(recepcion.ver(any(), anyLong(), anyLong())).willThrow(excepcion);
	}

	@Override
	protected MockHttpServletRequestBuilder pedidoQueFalla() {
		return get(SEDE + "/turnos/301");
	}

	static Stream<Arguments> operaciones() {
		return Stream.of(
				op("GET", "/ofertas/4/agenda?desde=2026-10-01&hasta=2026-10-07", null, 200),
				op("GET", "/agenda?fecha=2026-10-01", null, 200),
				op("POST", "/turnos/ofertas/4", ReservarTurnoRequest.class, 201),
				op("POST", "/turnos/301/confirmacion", null, 200),
				op("POST", "/turnos/301/cancelacion", CancelarTurnoRequest.class, 200),
				op("POST", "/turnos/301/reprogramacion", ReprogramarTurnoRequest.class, 200),
				op("POST", "/turnos/301/ausencia", RegistrarAusenciaRequest.class, 200),
				op("GET", "/turnos/301/historial", null, 200),
				op("GET", "/turnos/301", null, 200),
				op("GET", "/turnos?fecha=2026-10-01", null, 200),
				op("POST", "/turnos/301/llegada", null, 200),
				op("DELETE", "/turnos/301/llegada", null, 200),
				op("POST", "/series-de-turnos", "{\"ofertaId\":4,\"personaId\":128,\"diasSemana\":[1,3],"
						+ "\"hora\":\"10:00:00\",\"fechaDesde\":\"2026-10-05\",\"cantidad\":8}", 201),
				op("GET", "/series-de-turnos?personaId=128", null, 200),
				op("GET", "/series-de-turnos/2", null, 200),
				op("GET", "/series-de-turnos/2/alcance?alcance=ESTE_Y_SIGUIENTES&turnoId=301", null, 200),
				op("POST", "/series-de-turnos/2/cancelacion", CancelarSerieDeTurnosRequest.class, 200),
				op("POST", "/series-de-turnos/2/reprogramacion", ReprogramarSerieDeTurnosRequest.class, 200));
	}

	static Stream<Class<? extends Throwable>> excepciones() {
		// La ocurrencia sin lugar delega en el handler de su causa: tiene su propio test.
		return SliceDeController.excepcionesManejadas(SchedulingProblemHandler.class)
				.filter(tipo -> tipo != OcurrenciaSinLugarException.class);
	}

	@Test
	@DisplayName("una ocurrencia sin lugar responde como su causa, con la fecha de la ocurrencia")
	void ocurrencia_sin_lugar() throws Exception {
		given(series.crear(any(), anyLong(), any())).willThrow(new OcurrenciaSinLugarException(
				Instant.parse("2026-10-12T13:00:00Z"), new SlotNoDisponibleException("ocupado")));

		mockMvc.perform(post(SEDE + "/series-de-turnos").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ofertaId\":4,\"personaId\":128,\"diasSemana\":[1],"
								+ "\"hora\":\"10:00:00\",\"fechaDesde\":\"2026-10-05\",\"cantidad\":2}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.startsWith("https://akine.app/problems/")))
				.andExpect(jsonPath("$.ocurrenciaInicio").value("2026-10-12T13:00:00Z"));
	}

	@Test
	@DisplayName("la reserva repetida por clave de idempotencia es 200, no 201")
	void reserva_idempotente_es_200() throws Exception {
		var turno = Fixture.de(ResultadoDeReserva.class).turno();
		given(turnos.reservar(any(), anyLong(), anyLong(), any()))
				.willReturn(new ResultadoDeReserva(turno, false));

		mockMvc.perform(post(SEDE + "/turnos/ofertas/4").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content(cuerpo(ReservarTurnoRequest.class)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(1));
	}

	@Test
	@DisplayName("la agenda pasa el rango y el profesional opcional al servicio")
	void agenda_filtros() throws Exception {
		mockMvc.perform(get(SEDE + "/ofertas/4/agenda").with(miembro())
						.param("desde", "2026-10-01").param("hasta", "2026-10-07")
						.param("profesionalId", "15"))
				.andExpect(status().isOk());

		verify(agenda).buscar(any(), eq(7L), eq(4L), eq(LocalDate.of(2026, 10, 1)),
				eq(LocalDate.of(2026, 10, 7)), eq(15L));
	}

	@Test
	@DisplayName("una serie sin dias de la semana es 400 y no llega al servicio")
	void serie_sin_dias() throws Exception {
		mockMvc.perform(post(SEDE + "/series-de-turnos").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ofertaId\":4,\"personaId\":128,\"diasSemana\":[],\"hora\":\"10:00\","
								+ "\"fechaDesde\":\"2026-10-01\",\"cantidad\":4}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(series);
	}

	@Test
	@DisplayName("el alcance de una serie sin turno pivote llega null al servicio")
	void alcance_sin_pivote() throws Exception {
		mockMvc.perform(get(SEDE + "/series-de-turnos/2/alcance").with(miembro())
						.param("alcance", "ESTE_Y_SIGUIENTES"))
				.andExpect(status().isOk());

		verify(series).previsualizar(any(), eq(7L), eq(2L), any(), isNull());
	}

	@TestConfiguration
	static class Servicios {

		@Bean AgendaService agendaService() {
			return Fixture.mockQueResponde(AgendaService.class);
		}

		@Bean AgendaUnificadaService agendaUnificadaService() {
			return Fixture.mockQueResponde(AgendaUnificadaService.class);
		}

		@Bean SerieDeTurnosService serieDeTurnosService() {
			return Fixture.mockQueResponde(SerieDeTurnosService.class);
		}

		@Bean TurnoService turnoService() {
			return Fixture.mockQueResponde(TurnoService.class);
		}

		@Bean CicloDeTurnoService cicloDeTurnoService() {
			return Fixture.mockQueResponde(CicloDeTurnoService.class);
		}

		@Bean RecepcionService recepcionService() {
			return Fixture.mockQueResponde(RecepcionService.class);
		}

		@Bean CicloDeRecepcionService cicloDeRecepcionService() {
			return Fixture.mockQueResponde(CicloDeRecepcionService.class);
		}
	}
}
