package com.akine.scheduling.api;

import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.scheduling.api.dto.CancelarSerieDeTurnosRequest;
import com.akine.scheduling.api.dto.CancelarTurnoRequest;
import com.akine.scheduling.api.dto.RegistrarAusenciaRequest;
import com.akine.scheduling.api.dto.ReprogramarSerieDeTurnosRequest;
import com.akine.scheduling.api.dto.ReprogramarTurnoRequest;
import com.akine.scheduling.api.dto.ReservarTurnoRequest;
import com.akine.scheduling.application.AgendaOperativaService;
import com.akine.scheduling.application.AgendaOperativaView;
import com.akine.scheduling.application.AgendaService;
import com.akine.scheduling.application.AgendaUnificadaService;
import com.akine.scheduling.application.AltaDeSerieCommand;
import com.akine.scheduling.application.AltaEnListaDeEsperaCommand;
import com.akine.scheduling.application.BusquedaDeDisponibilidad;
import com.akine.scheduling.application.CicloDeRecepcionService;
import com.akine.scheduling.application.CicloDeTurnoService;
import com.akine.scheduling.application.ContextoDeAgendaService;
import com.akine.scheduling.application.ContextoDeAgendaView;
import com.akine.scheduling.application.DisponibilidadDeTurnosService;
import com.akine.scheduling.application.DisponibilidadView;
import com.akine.scheduling.application.EntradaDeEsperaView;
import com.akine.scheduling.application.EntradasDeEsperaPagina;
import com.akine.scheduling.application.ListaDeEsperaService;
import com.akine.scheduling.application.PrepagoView;
import com.akine.scheduling.application.PrevisualizacionDeSerieView;
import com.akine.scheduling.application.ResultadoDeSerie;
import com.akine.scheduling.application.SerieView;
import com.akine.scheduling.application.TurnoDelDiaView;
import com.akine.scheduling.application.PrevisualizacionDeSerieService;
import com.akine.scheduling.application.RecepcionService;
import com.akine.scheduling.application.ResultadoDeReserva;
import com.akine.scheduling.application.SerieDeTurnosService;
import com.akine.scheduling.application.TurnoService;
import com.akine.scheduling.application.ValidacionDeHorarioCommand;
import com.akine.scheduling.application.ValidacionDeHorarioView;
import com.akine.scheduling.domain.EstadoListaDeEspera;
import com.akine.scheduling.domain.OrigenDeTurno;
import com.akine.scheduling.domain.PrioridadListaDeEspera;
import com.akine.scheduling.domain.exception.EntradaDeEsperaCerradaException;
import com.akine.scheduling.domain.exception.OfertaNoAgendableException;
import com.akine.scheduling.domain.exception.RecursoOcupadoException;
import com.akine.scheduling.domain.exception.SlotCompletoException;
import com.akine.testsupport.Fixture;
import com.akine.testsupport.SliceDeController;
import com.akine.testsupport.SliceRestDeModulo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Stream;

import static com.akine.testsupport.SliceDeController.miembro;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
 * Capa REST de {@code scheduling} (M12, M13): turnos, agenda, agenda unificada y series. La
 * recepción tiene su propio slice ({@code RecepcionControllerTest}).
 */
@WebMvcTest({AgendaController.class, AgendaUnificadaController.class, SerieDeTurnosController.class,
		TurnoController.class, DisponibilidadDeTurnosController.class, ListaDeEsperaController.class})
@Import({SliceDeController.Seguridad.class, SchedulingApiActor.class,
		SchedulingControllersTest.Servicios.class})
class SchedulingControllersTest extends SliceRestDeModulo {

	private static final String SEDE = "/api/v1/consultorios/7";
	private static final String SERIE_MINIMA = "{\"ofertaId\":4,\"personaId\":128,\"diasSemana\":[1,3],"
			+ "\"hora\":\"10:00:00\",\"fechaDesde\":\"2026-10-05\",\"cantidad\":8}";
	private static final Instant NUEVE = Instant.parse("2026-10-05T12:00:00Z");

	@Autowired private AgendaService agenda;
	@Autowired private AgendaUnificadaService agendaUnificada;
	@Autowired private SerieDeTurnosService series;
	@Autowired private TurnoService turnos;
	@Autowired private CicloDeTurnoService ciclo;
	@Autowired private RecepcionService recepcion;
	@Autowired private CicloDeRecepcionService cicloDeRecepcion;
	@Autowired private DisponibilidadDeTurnosService disponibilidad;
	@Autowired private ContextoDeAgendaService contexto;
	@Autowired private AgendaOperativaService agendaOperativa;
	@Autowired private ListaDeEsperaService listaDeEspera;
	@Autowired private PrevisualizacionDeSerieService previsualizacion;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@Override
	protected String prefijo() {
		return SEDE;
	}

	@Override
	protected Object[] servicios() {
		return new Object[] {agenda, agendaUnificada, series, turnos, ciclo, recepcion, cicloDeRecepcion,
				disponibilidad, contexto, agendaOperativa, listaDeEspera, previsualizacion};
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
				op("GET", "/turnos/calendario?desde=2026-10-01&hasta=2026-10-08", null, 200),
				op("POST", "/turnos/301/llegada", null, 200),
				op("DELETE", "/turnos/301/llegada", null, 200),
				op("POST", "/series-de-turnos", "{\"ofertaId\":4,\"personaId\":128,\"diasSemana\":[1,3],"
						+ "\"hora\":\"10:00:00\",\"fechaDesde\":\"2026-10-05\",\"cantidad\":8}", 201),
				op("GET", "/series-de-turnos?personaId=128", null, 200),
				op("GET", "/series-de-turnos/2", null, 200),
				op("GET", "/series-de-turnos/2/alcance?alcance=ESTE_Y_SIGUIENTES&turnoId=301", null, 200),
				op("POST", "/series-de-turnos/2/cancelacion", CancelarSerieDeTurnosRequest.class, 200),
				op("POST", "/series-de-turnos/2/reprogramacion", ReprogramarSerieDeTurnosRequest.class, 200),
				op("POST", "/series-de-turnos/previsualizacion", SERIE_MINIMA, 200),
				op("POST", "/turnos/301/contexto", "{\"casoId\":9,\"planId\":57,\"expectedVersion\":1}", 200),
				op("GET", "/turnos/personas/128?soloFuturos=true&page=1&size=5", null, 200),
				op("POST", "/disponibilidad/busqueda", "{\"ofertaId\":4,\"dias\":[1,3],\"limite\":1}", 200),
				op("POST", "/disponibilidad/validacion", "{\"ofertaId\":4,\"inicio\":\"2026-10-05T12:00:00Z\"}", 200),
				op("GET", "/disponibilidad/profesionales?ofertaId=4", null, 200),
				op("GET", "/personas/128/contexto-de-agenda", null, 200),
				op("GET", "/agenda/operativa?desde=2026-10-05&hasta=2026-10-12", null, 200),
				op("POST", "/lista-de-espera", "{\"personaId\":128,\"ofertaId\":4,\"fechaDesde\":\"2026-11-02\","
						+ "\"fechaHasta\":\"2026-11-30\",\"dias\":[1,5],\"prioridad\":\"URGENTE\"}", 201),
				op("GET", "/lista-de-espera?estado=ACTIVA&ofertaId=4&page=1&size=5", null, 200),
				op("GET", "/lista-de-espera/coincidencias?turnoId=301", null, 200),
				op("POST", "/lista-de-espera/900/asignacion", "{\"inicio\":\"2026-10-05T12:00:00Z\","
						+ "\"profesionalId\":31,\"expectedVersion\":0}", 200),
				op("POST", "/lista-de-espera/900/descarte", "{\"motivo\":\"Ya consiguio\",\"expectedVersion\":0}", 200));
	}

	static Stream<Class<? extends Throwable>> excepciones() {
		// La ocurrencia sin lugar delega en el handler de su causa: tiene su propio test.
		return SliceDeController.excepcionesManejadas(SchedulingProblemHandler.class)
				.filter(tipo -> tipo != OcurrenciaSinLugarException.class);
	}

	static Stream<Arguments> causasDeOcurrenciaSinLugar() {
		return Stream.of(
				Arguments.of(new SlotNoDisponibleException("ocupado"), "slot-no-disponible"),
				Arguments.of(new SlotCompletoException(1), "slot-completo"),
				Arguments.of(new RecursoOcupadoException("profesional"), "recurso-ocupado"),
				Arguments.of(new OfertaNoAgendableException(4L, "esta dada de baja"), "oferta-no-agendable"));
	}

	@ParameterizedTest(name = "{1}")
	@MethodSource("causasDeOcurrenciaSinLugar")
	@DisplayName("una ocurrencia sin lugar responde como su causa, con la fecha de la ocurrencia")
	void ocurrencia_sin_lugar(RuntimeException causa, String tipo) throws Exception {
		given(series.crear(any(), anyLong(), any())).willThrow(new OcurrenciaSinLugarException(
				Instant.parse("2026-10-12T13:00:00Z"), causa));

		mockMvc.perform(post(SEDE + "/series-de-turnos").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ofertaId\":4,\"personaId\":128,\"diasSemana\":[1],"
								+ "\"hora\":\"10:00:00\",\"fechaDesde\":\"2026-10-05\",\"cantidad\":2}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/" + tipo))
				.andExpect(jsonPath("$.ocurrenciaInicio").value("2026-10-12T13:00:00Z"));
	}

	@Test
	@DisplayName("una serie repetida por clave de idempotencia es 200, no 201, y las ocurrencias elegidas llegan al servicio")
	void serie_idempotente_con_ocurrencias_elegidas() throws Exception {
		given(series.crear(any(), anyLong(), any())).willReturn(new ResultadoDeSerie(Fixture.de(SerieView.class), false));

		mockMvc.perform(post(SEDE + "/series-de-turnos").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ofertaId\":4,\"personaId\":128,\"diasSemana\":[1],\"hora\":\"10:00:00\","
								+ "\"fechaDesde\":\"2026-10-05\",\"cantidad\":2,\"casoId\":9,\"planId\":57,\"origen\":\"PLAN\","
								+ "\"ocurrencias\":[{\"inicio\":\"2026-10-05T13:00:00Z\",\"profesionalId\":31},"
								+ "{\"inicio\":\"2026-10-12T13:00:00Z\"}]}"))
				.andExpect(status().isOk());

		ArgumentCaptor<AltaDeSerieCommand> alta = ArgumentCaptor.forClass(AltaDeSerieCommand.class);
		verify(series).crear(any(), eq(7L), alta.capture());
		assertThat(alta.getValue().ocurrencias()).containsExactly(
				new AltaDeSerieCommand.OcurrenciaElegida(Instant.parse("2026-10-05T13:00:00Z"), 31L),
				new AltaDeSerieCommand.OcurrenciaElegida(Instant.parse("2026-10-12T13:00:00Z"), null));
		assertThat(alta.getValue().planId()).isEqualTo(57L);
		assertThat(alta.getValue().origen()).isEqualTo(OrigenDeTurno.PLAN);
	}

	@Test
	@DisplayName("la previsualizacion proyecta la sugerencia, y una ocurrencia sin sugerencia la omite")
	void previsualizacion_por_el_json() throws Exception {
		given(previsualizacion.previsualizar(any(), anyLong(), any())).willReturn(new PrevisualizacionDeSerieView(
				4L, "Kinesiologia", 45, "America/Argentina/Cordoba", 2, List.of(
				new PrevisualizacionDeSerieView.OcurrenciaPrevistaView(1, NUEVE, NUEVE.plusSeconds(2700), 31L, "Perez, Ana",
						"Box 1", "DISPONIBLE", null, null),
				new PrevisualizacionDeSerieView.OcurrenciaPrevistaView(2, NUEVE, NUEVE.plusSeconds(2700), 31L, "Perez, Ana",
						null, "NO_DISPONIBLE", "Ese horario ya no tiene lugar.", horario()))));

		mockMvc.perform(post(SEDE + "/series-de-turnos/previsualizacion").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content(SERIE_MINIMA))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pendientesDelPlan").value(2))
				.andExpect(jsonPath("$.ocurrencias[0].estado").value("DISPONIBLE"))
				.andExpect(jsonPath("$.ocurrencias[0].sugerencia").doesNotExist())
				.andExpect(jsonPath("$.ocurrencias[1].motivo").value("Ese horario ya no tiene lugar."))
				.andExpect(jsonPath("$.ocurrencias[1].sugerencia.profesionalNombre").value("Perez, Ana"));
	}

	// =================================================================================
	// Disponibilidad y agenda operativa
	// =================================================================================

	@Test
	@DisplayName("la busqueda traduce los dias ISO y publica los horarios con sus nombres")
	void busqueda_por_el_json() throws Exception {
		given(disponibilidad.buscar(any(), anyLong(), any())).willReturn(new DisponibilidadView(
				4L, "Kinesiologia", 45, true, "America/Argentina/Cordoba", LocalDate.of(2026, 10, 19), true,
				List.of("FERIADO"), List.of(horario())));

		mockMvc.perform(post(SEDE + "/disponibilidad/busqueda").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ofertaId\":4,\"profesionalId\":31,\"desde\":\"2026-10-05\",\"hasta\":\"2026-10-19\","
								+ "\"horaDesde\":\"09:00:00\",\"horaHasta\":\"12:00:00\",\"dias\":[1,5],\"limite\":10}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.truncado").value(true))
				.andExpect(jsonPath("$.motivosSinHorario[0]").value("FERIADO"))
				.andExpect(jsonPath("$.buscadoHasta").value("2026-10-19"))
				.andExpect(jsonPath("$.horarios[0].profesionalNombre").value("Perez, Ana"))
				.andExpect(jsonPath("$.horarios[0].espacioNombre").value("Box 1"))
				.andExpect(jsonPath("$.horarios[0].cupoLibre").value(1));

		ArgumentCaptor<BusquedaDeDisponibilidad> busqueda = ArgumentCaptor.forClass(BusquedaDeDisponibilidad.class);
		verify(disponibilidad).buscar(any(), eq(7L), busqueda.capture());
		assertThat(busqueda.getValue().dias()).containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
		assertThat(busqueda.getValue().horaHasta()).isEqualTo(LocalTime.of(12, 0));
		assertThat(busqueda.getValue().limite()).isEqualTo(10);
	}

	@Test
	@DisplayName("sin dias la busqueda no filtra; un dia fuera de 1..7 o un limite fuera de rango es 400 y no llega al servicio")
	void busqueda_validaciones() throws Exception {
		for (String invalida : List.of("{\"ofertaId\":4,\"dias\":[8]}", "{\"ofertaId\":4,\"limite\":201}",
				"{\"ofertaId\":4,\"limite\":0}", "{\"dias\":[1]}", "{\"ofertaId\":-1}")) {
			mockMvc.perform(post(SEDE + "/disponibilidad/busqueda").with(miembro())
							.contentType(MediaType.APPLICATION_JSON).content(invalida))
					.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(disponibilidad);

		mockMvc.perform(post(SEDE + "/disponibilidad/busqueda").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content("{\"ofertaId\":4}"))
				.andExpect(status().isOk());
		ArgumentCaptor<BusquedaDeDisponibilidad> busqueda = ArgumentCaptor.forClass(BusquedaDeDisponibilidad.class);
		verify(disponibilidad).buscar(any(), eq(7L), busqueda.capture());
		assertThat(busqueda.getValue().dias()).isEmpty();
	}

	@Test
	@DisplayName("un horario que no se puede es 200 con NO_DISPONIBLE, la causa, el choque y las alternativas")
	void validacion_no_disponible_por_el_json() throws Exception {
		given(disponibilidad.validar(any(), anyLong(), any())).willReturn(new ValidacionDeHorarioView(
				"NO_DISPONIBLE", "PROFESIONAL_OCUPADO", "El profesional ya tiene otro turno en ese horario.",
				NUEVE, NUEVE.plusSeconds(2700), List.of(), List.of(), 300L, NUEVE, NUEVE.plusSeconds(3600),
				List.of(), List.of(horario())));

		mockMvc.perform(post(SEDE + "/disponibilidad/validacion").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ofertaId\":4,\"inicio\":\"2026-10-05T12:00:00Z\",\"profesionalId\":31,\"espacioId\":55,"
								+ "\"personaId\":128,\"casoId\":9,\"planId\":57,\"turnoExcluidoId\":300}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("NO_DISPONIBLE"))
				.andExpect(jsonPath("$.causa").value("PROFESIONAL_OCUPADO"))
				.andExpect(jsonPath("$.turnoEnConflictoId").value(300))
				.andExpect(jsonPath("$.alternativas[0].profesionalId").value(31));

		verify(disponibilidad).validar(any(), eq(7L), eq(new ValidacionDeHorarioCommand(
				4L, NUEVE, 31L, 55L, 128L, 9L, 57L, 300L)));
	}

	@Test
	@DisplayName("un horario libre publica profesionales, boxes y avisos, sin causa")
	void validacion_disponible_por_el_json() throws Exception {
		given(disponibilidad.validar(any(), anyLong(), any())).willReturn(new ValidacionDeHorarioView(
				"DISPONIBLE_CON_AVISO", null, null, NUEVE, NUEVE.plusSeconds(2700),
				List.of(new ValidacionDeHorarioView.RecursoLibreView(31L, "Perez, Ana")),
				List.of(new ValidacionDeHorarioView.RecursoLibreView(55L, "Box 1")),
				null, null, null, List.of("El turno queda con un profesional distinto del responsable del caso."), List.of()));

		mockMvc.perform(post(SEDE + "/disponibilidad/validacion").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"ofertaId\":4,\"inicio\":\"2026-10-05T12:00:00Z\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.causa").doesNotExist())
				.andExpect(jsonPath("$.profesionales[0].nombre").value("Perez, Ana"))
				.andExpect(jsonPath("$.espaciosLibres[0].id").value(55))
				.andExpect(jsonPath("$.avisos[0]").value(org.hamcrest.Matchers.containsString("responsable")));
	}

	@Test
	@DisplayName("una validacion sin horario es 400 y no llega al servicio")
	void validacion_sin_horario() throws Exception {
		mockMvc.perform(post(SEDE + "/disponibilidad/validacion").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content("{\"ofertaId\":4}"))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(disponibilidad);
	}

	@Test
	@DisplayName("los profesionales se publican por id y nombre, y sin oferta son los de la sede")
	void profesionales_por_el_json() throws Exception {
		given(disponibilidad.profesionales(any(), anyLong(), isNull())).willReturn(List.of(
				new ValidacionDeHorarioView.RecursoLibreView(31L, "Perez, Ana")));

		mockMvc.perform(get(SEDE + "/disponibilidad/profesionales").with(miembro()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(31))
				.andExpect(jsonPath("$[0].nombre").value("Perez, Ana"));
	}

	@Test
	@DisplayName("el contexto de agenda proyecta casos, planes y conteos, y no lleva nada clinico")
	void contexto_de_agenda_por_el_json() throws Exception {
		given(contexto.dePersona(any(), anyLong(), anyLong())).willReturn(new ContextoDeAgendaView(128L, 31L, List.of(
				new ContextoDeAgendaView.CasoDeAgendaView(9L, 3, 4L, "Kinesiologia", 31L, "Perez, Ana", List.of(
						new ContextoDeAgendaView.PlanDeAgendaView(57L, 2, 3, 4, List.of(
								new ContextoDeAgendaView.SesionesDePrestacionView(4L, "Kinesiologia", 10, 2, 3, 1, 4))))))));

		mockMvc.perform(get(SEDE + "/personas/128/contexto-de-agenda").with(miembro()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ultimoProfesionalId").value(31))
				.andExpect(jsonPath("$.casos[0].numeroCaso").value(3))
				.andExpect(jsonPath("$.casos[0].responsableNombre").value("Perez, Ana"))
				.andExpect(jsonPath("$.casos[0].planes[0].numeroPlan").value(2))
				.andExpect(jsonPath("$.casos[0].planes[0].prestaciones[0].pendientes").value(4))
				.andExpect(jsonPath("$.casos[0].planes[0].prestaciones[0].ausenciasQueConsumen").value(1))
				.andExpect(jsonPath("$.casos[0].diagnostico").doesNotExist());
	}

	@Test
	@DisplayName("la agenda operativa exige la ventana, pasa los filtros y proyecta cada parte de la grilla")
	void agenda_operativa_por_el_json() throws Exception {
		given(agendaOperativa.ver(any(), anyLong(), any(), any(), any(), any(), any(), anyBoolean())).willReturn(new AgendaOperativaView(
				LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 6), "America/Argentina/Cordoba",
				List.of(new AgendaOperativaView.ProfesionalDeAgendaView(31L, "Perez, Ana",
						List.of(new AgendaOperativaView.FranjaDeAgendaView(NUEVE, NUEVE.plusSeconds(3600))))),
				List.of(new AgendaOperativaView.EspacioDeAgendaView(55L, "Box 1", "BOX")),
				List.of(),
				List.of(new AgendaOperativaView.ClaseEnAgendaView(8L, NUEVE, NUEVE.plusSeconds(3600), "PROGRAMADA", "Pilates",
						"Pilates grupal", 31L, 55L, 8, 3)),
				List.of(new AgendaOperativaView.BloqueoEnAgendaView(6L, 31L, "CAPACITACION", "Curso", NUEVE, NUEVE.plusSeconds(3600)))));

		mockMvc.perform(get(SEDE + "/agenda/operativa").with(miembro())
						.param("desde", "2026-10-05").param("hasta", "2026-10-06")
						.param("profesionalId", "31").param("ofertaId", "4").param("espacioId", "55")
						.param("incluirCancelados", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.profesionales[0].franjas[0].desde").value("2026-10-05T12:00:00Z"))
				.andExpect(jsonPath("$.espacios[0].nombre").value("Box 1"))
				.andExpect(jsonPath("$.clases[0].ocupados").value(3))
				.andExpect(jsonPath("$.bloqueos[0].motivo").value("CAPACITACION"));

		verify(agendaOperativa).ver(any(), eq(7L), eq(LocalDate.of(2026, 10, 5)), eq(LocalDate.of(2026, 10, 6)),
				eq(31L), eq(4L), eq(55L), eq(true));

		mockMvc.perform(get(SEDE + "/agenda/operativa").with(miembro()).param("desde", "2026-10-05"))
				.andExpect(status().isBadRequest());
	}

	// =================================================================================
	// Lista de espera y contexto del turno
	// =================================================================================

	@Test
	@DisplayName("anotar traduce los dias ISO y responde 201 con Location")
	void anotar_por_el_json() throws Exception {
		given(listaDeEspera.anotar(any(), anyLong(), any())).willReturn(entrada());

		mockMvc.perform(post(SEDE + "/lista-de-espera").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"personaId\":128,\"ofertaId\":4,\"profesionalId\":31,\"casoId\":9,\"planId\":57,"
								+ "\"fechaDesde\":\"2026-11-02\",\"fechaHasta\":\"2026-11-30\",\"dias\":[1,5],"
								+ "\"horaDesde\":\"08:00:00\",\"horaHasta\":\"12:00:00\",\"prioridad\":\"ALTA\",\"notas\":\"Prefiere a la manana\"}"))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", SEDE + "/lista-de-espera/900"))
				.andExpect(jsonPath("$.personaNombre").value("Perez, Ana"))
				.andExpect(jsonPath("$.telefono").value("351-555-0000"))
				.andExpect(jsonPath("$.prioridad").value("ALTA"))
				.andExpect(jsonPath("$.dias[1]").value(5))
				.andExpect(jsonPath("$.horaHasta").value("12:00:00"));

		ArgumentCaptor<AltaEnListaDeEsperaCommand> alta = ArgumentCaptor.forClass(AltaEnListaDeEsperaCommand.class);
		verify(listaDeEspera).anotar(any(), eq(7L), alta.capture());
		assertThat(alta.getValue().dias()).containsExactlyInAnyOrder(DayOfWeek.MONDAY, DayOfWeek.FRIDAY);
		assertThat(alta.getValue().prioridad()).isEqualTo(PrioridadListaDeEspera.ALTA);
	}

	@Test
	@DisplayName("sin dias es cualquiera; sin fechas, con unas notas larguisimas o una prioridad inventada es 400")
	void anotar_validaciones() throws Exception {
		String largas = "x".repeat(301);
		for (String invalida : List.of("{\"personaId\":128,\"ofertaId\":4}",
				"{\"personaId\":128,\"ofertaId\":4,\"fechaDesde\":\"2026-11-02\",\"fechaHasta\":\"2026-11-30\",\"notas\":\"" + largas + "\"}",
				"{\"personaId\":128,\"ofertaId\":4,\"fechaDesde\":\"2026-11-02\",\"fechaHasta\":\"2026-11-30\",\"dias\":[0]}",
				"{\"personaId\":128,\"ofertaId\":4,\"fechaDesde\":\"2026-11-02\",\"fechaHasta\":\"2026-11-30\",\"prioridad\":\"INVENTADA\"}")) {
			mockMvc.perform(post(SEDE + "/lista-de-espera").with(miembro())
							.contentType(MediaType.APPLICATION_JSON).content(invalida))
					.andExpect(status().isBadRequest());
		}
		verifyNoInteractions(listaDeEspera);

		mockMvc.perform(post(SEDE + "/lista-de-espera").with(miembro())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"personaId\":128,\"ofertaId\":4,\"fechaDesde\":\"2026-11-02\",\"fechaHasta\":\"2026-11-30\"}"))
				.andExpect(status().isCreated());
		ArgumentCaptor<AltaEnListaDeEsperaCommand> alta = ArgumentCaptor.forClass(AltaEnListaDeEsperaCommand.class);
		verify(listaDeEspera).anotar(any(), eq(7L), alta.capture());
		assertThat(alta.getValue().dias()).isEmpty();
	}

	@Test
	@DisplayName("el listado es paginado, con filtros por estado, oferta y persona, y un size fuera de rango es 400")
	void listar_lista_de_espera() throws Exception {
		given(listaDeEspera.listar(any(), anyLong(), any(), any(), any(), anyInt(), anyInt()))
				.willReturn(new EntradasDeEsperaPagina(List.of(entrada()), 21L));

		mockMvc.perform(get(SEDE + "/lista-de-espera").with(miembro())
						.param("estado", "ASIGNADA").param("ofertaId", "4").param("personaId", "128")
						.param("page", "2").param("size", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content[0].id").value(900))
				.andExpect(jsonPath("$.page").value(2))
				.andExpect(jsonPath("$.size").value(10))
				.andExpect(jsonPath("$.totalElements").value(21));

		verify(listaDeEspera).listar(any(), eq(7L), eq(EstadoListaDeEspera.ASIGNADA), eq(4L), eq(128L), eq(2), eq(10));

		mockMvc.perform(get(SEDE + "/lista-de-espera").with(miembro()).param("size", "101"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("las coincidencias aceptan un turno, o una oferta con su horario")
	void coincidencias_por_el_json() throws Exception {
		given(listaDeEspera.coincidencias(any(), anyLong(), any(), any(), any(), any())).willReturn(List.of(entrada()));

		mockMvc.perform(get(SEDE + "/lista-de-espera/coincidencias").with(miembro())
						.param("ofertaId", "4").param("inicio", "2026-10-05T12:00:00Z").param("profesionalId", "31"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(900));

		verify(listaDeEspera).coincidencias(any(), eq(7L), isNull(), eq(4L), eq(NUEVE), eq(31L));
	}

	@Test
	@DisplayName("una entrada resuelta es 409 con su tipo, y asignar o descartar sin version es 400")
	void entrada_cerrada() throws Exception {
		given(listaDeEspera.descartar(any(), anyLong(), anyLong(), any(), anyLong()))
				.willThrow(new EntradaDeEsperaCerradaException(900L, "ASIGNADA"));

		mockMvc.perform(post(SEDE + "/lista-de-espera/900/descarte").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/lista-de-espera-cerrada"));

		mockMvc.perform(post(SEDE + "/lista-de-espera/900/descarte").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"x\"}"))
				.andExpect(status().isBadRequest());
		mockMvc.perform(post(SEDE + "/lista-de-espera/900/asignacion").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content("{\"inicio\":\"2026-10-05T12:00:00Z\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("asociar contexto pasa caso, plan y version, omitirlos desasocia, y sin version es 400")
	void asociar_contexto() throws Exception {
		mockMvc.perform(post(SEDE + "/turnos/301/contexto").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":2}"))
				.andExpect(status().isOk());
		verify(turnos).asociarContexto(any(), eq(7L), eq(301L), isNull(), isNull(), eq(2L));

		mockMvc.perform(post(SEDE + "/turnos/301/contexto").with(miembro())
						.contentType(MediaType.APPLICATION_JSON).content("{\"casoId\":9}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("los turnos de una persona pasan soloFuturos y la pagina; un size fuera de rango es 400")
	void turnos_de_una_persona() throws Exception {
		mockMvc.perform(get(SEDE + "/turnos/personas/128").with(miembro())
						.param("soloFuturos", "true").param("page", "1").param("size", "5"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.turnos[0].profesionalNombre").value("profesionalNombre"));
		verify(recepcion).deLaPersona(any(), eq(7L), eq(128L), eq(true), eq(1), eq(5));

		mockMvc.perform(get(SEDE + "/turnos/personas/128").with(miembro()).param("size", "101"))
				.andExpect(status().isBadRequest());
	}

	private static DisponibilidadView.HorarioDisponibleView horario() {
		return new DisponibilidadView.HorarioDisponibleView(NUEVE, NUEVE.plusSeconds(2700), 31L, "Perez, Ana", 55L, "Box 1", 1);
	}

	private static EntradaDeEsperaView entrada() {
		return new EntradaDeEsperaView(900L, 128L, "Perez, Ana", "DNI 30111222", "351-555-0000", 4L, "Kinesiologia", 31L, "Lic. Ruiz",
				9L, 57L, LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 30), List.of(1, 5), LocalTime.of(8, 0), LocalTime.of(12, 0),
				"ALTA", "Prefiere a la manana", "ACTIVA", null, null, Instant.parse("2026-10-30T12:00:00Z"), 0L);
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
	@DisplayName("05.02: el turno leido por enlace directo trae la cobertura elegida al reservar, y la omite si no hubo")
	void ver_turno_trae_la_cobertura_elegida() throws Exception {
		given(recepcion.ver(any(), eq(7L), eq(301L))).willReturn(turnoDelDia(301L, 412L));
		given(recepcion.ver(any(), eq(7L), eq(302L))).willReturn(turnoDelDia(302L, null));

		mockMvc.perform(get(SEDE + "/turnos/301").with(miembro()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.coberturaId").value(412));
		mockMvc.perform(get(SEDE + "/turnos/302").with(miembro()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.coberturaId").doesNotExist());
	}

	private static TurnoDelDiaView turnoDelDia(long id, Long coberturaId) {
		return new TurnoDelDiaView(id, Instant.parse("2026-10-05T12:00:00Z"),
				Instant.parse("2026-10-05T12:45:00Z"), "RESERVADO", 128L, "Perez, Ana", "DNI 30111222",
				4L, "Kinesiologia", 31L, null, null, null, null, coberturaId, 0L, null,
				new PrepagoView(PrepagoView.NO_EXIGIDO, null, null, null, null, null), null);
	}

	@Test
	@DisplayName("DP-45: el calendario pasa el rango, los filtros y la bandera de cancelados al servicio")
	void calendario_filtros() throws Exception {
		mockMvc.perform(get(SEDE + "/turnos/calendario").with(miembro())
						.param("desde", "2026-10-01").param("hasta", "2026-10-08")
						.param("profesionalId", "31").param("ofertaId", "4")
						.param("incluirCancelados", "true"))
				.andExpect(status().isOk());
		mockMvc.perform(get(SEDE + "/turnos/calendario").with(miembro())
						.param("desde", "2026-10-01"))
				.andExpect(status().isBadRequest());

		verify(recepcion).delRango(any(), eq(7L), eq(LocalDate.of(2026, 10, 1)),
				eq(LocalDate.of(2026, 10, 8)), eq(31L), eq(4L), eq(true));
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

		@Bean PrevisualizacionDeSerieService previsualizacionDeSerieService() {
			return Fixture.mockQueResponde(PrevisualizacionDeSerieService.class);
		}

		@Bean DisponibilidadDeTurnosService disponibilidadDeTurnosService() {
			return Fixture.mockQueResponde(DisponibilidadDeTurnosService.class);
		}

		@Bean ContextoDeAgendaService contextoDeAgendaService() {
			return Fixture.mockQueResponde(ContextoDeAgendaService.class);
		}

		@Bean AgendaOperativaService agendaOperativaService() {
			return Fixture.mockQueResponde(AgendaOperativaService.class);
		}

		@Bean ListaDeEsperaService listaDeEsperaService() {
			return Fixture.mockQueResponde(ListaDeEsperaService.class);
		}

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
