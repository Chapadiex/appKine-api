package com.akine.activity.api;

import com.akine.activity.application.AsistenciaEventoView;
import com.akine.activity.application.AsistenciaService;
import com.akine.activity.application.AsistenciaView;
import com.akine.activity.application.ClaseService;
import com.akine.activity.application.ClaseView;
import com.akine.activity.application.CuposView;
import com.akine.activity.application.DetalleOperativoView;
import com.akine.activity.application.EventoDeClaseView;
import com.akine.activity.application.IngresoSinInscripcionCommand;
import com.akine.activity.application.InscribirCommand;
import com.akine.activity.application.InscripcionService;
import com.akine.activity.application.InscripcionView;
import com.akine.activity.application.ItemDeLote;
import com.akine.activity.application.OperatingActor;
import com.akine.activity.application.ParticipanteView;
import com.akine.activity.application.ProgramarClaseCommand;
import com.akine.activity.application.RegistrarAsistenciaCommand;
import com.akine.activity.application.ReprogramarClaseCommand;
import com.akine.activity.application.ResultadoDeAsistencia;
import com.akine.activity.application.ResultadoDeCancelacion;
import com.akine.activity.application.ResultadoDeCierre;
import com.akine.activity.application.ResultadoDeInscripcion;
import com.akine.activity.application.ResultadoDeLote;
import com.akine.activity.application.ResultadoDeProgramacion;
import com.akine.activity.domain.OrigenAsistencia;
import com.akine.activity.domain.ResultadoAsistencia;
import com.akine.activity.domain.exception.ClaseCompletaException;
import com.akine.activity.domain.exception.ClaseNotAccessibleException;
import com.akine.platform.spi.problem.ProblemType;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.tenant.TenantOperationalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La capa REST de clases, inscripciones y asistencia (M28, F9).
 *
 * <h2>Que decide esta capa, y que no</h2>
 *
 * <p>No decide reglas: traduce. Lo que solo ella puede romper es <b>el codigo de respuesta segun
 * el desenlace</b> —201 con {@code Location} al crear, 200 al reintentar sin crear—, que el origen
 * de la asistencia lo ponga el endpoint y no el cliente, el recorte de la paginacion y la
 * validacion de los cuerpos. Los servicios estan mockeados: sus reglas las cubren sus propios
 * tests.
 */
@WebMvcTest({ClaseController.class, InscripcionController.class, AsistenciaController.class})
@Import({ActivityApiSliceSecurityConfig.class, ActivityApiActor.class})
@DisplayName("Controllers de activity")
class ActivityControllersTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 10L;
	private static final long CLASE_ID = 77L;
	private static final long CUENTA = 99L;
	private static final String CLASES = "/api/v1/consultorios/10/clases";
	private static final String CLASE = CLASES + "/77";

	private static final Instant INICIO = Instant.now().plus(7, ChronoUnit.DAYS)
			.truncatedTo(ChronoUnit.SECONDS);

	@Autowired private MockMvc mockMvc;

	@MockitoBean private ClaseService claseService;
	@MockitoBean private InscripcionService inscripcionService;
	@MockitoBean private AsistenciaService asistenciaService;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@BeforeEach
	void setUp() {
		given(tenantContextHolder.current()).willReturn(Optional.of(new RequestTenantContext(
				CUENTA, ORG_ID, SEDE_ID, "ORG_ADMIN", TenantOperationalStatus.ACTIVA)));
	}

	// =================================================================================
	// Actor
	// =================================================================================

	@Test
	@DisplayName("El actor sale de la sesion y del contexto de trabajo, no del cuerpo del pedido")
	void el_actor_sale_de_la_sesion() throws Exception {
		given(claseService.ver(any(), anyLong(), anyLong())).willReturn(clase());

		autenticado(get(CLASE)).andExpect(status().isOk());

		ArgumentCaptor<OperatingActor> actor = ArgumentCaptor.forClass(OperatingActor.class);
		verify(claseService).ver(actor.capture(), eq(SEDE_ID), eq(CLASE_ID));
		assertThat(actor.getValue()).isEqualTo(new OperatingActor(CUENTA, false, ORG_ID, SEDE_ID));
	}

	/** 403 y nunca 401: un 401 deja al frontend en bucle de login. */
	@Test
	@DisplayName("Sin principal de AKINE la operacion es 403 y el servicio no se llama")
	void sin_sesion_es_403() throws Exception {
		mockMvc.perform(get(CLASE)).andExpect(status().isForbidden());

		verify(claseService, never()).ver(any(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("Sin contexto de trabajo el actor viaja sin organizacion: lo rechaza el servicio")
	void sin_contexto_viaja_sin_organizacion() throws Exception {
		given(tenantContextHolder.current()).willReturn(Optional.empty());
		given(claseService.ver(any(), anyLong(), anyLong())).willReturn(clase());

		autenticado(get(CLASE)).andExpect(status().isOk());

		ArgumentCaptor<OperatingActor> actor = ArgumentCaptor.forClass(OperatingActor.class);
		verify(claseService).ver(actor.capture(), anyLong(), anyLong());
		assertThat(actor.getValue().contextOrganizationId()).isNull();
	}

	// =================================================================================
	// Clases
	// =================================================================================

	@Nested
	@DisplayName("Clases")
	class Clases {

		@Test
		@DisplayName("Programar devuelve 201 con Location; el reintento idempotente devuelve 200")
		void programar_201_y_200() throws Exception {
			given(claseService.programar(any(), anyLong(), anyLong(), any()))
					.willReturn(new ResultadoDeProgramacion(clase(), true))
					.willReturn(new ResultadoDeProgramacion(clase(), false));
			String cuerpo = """
					{"inicio":"%s","fin":"%s","profesionalId":31,"capacidad":8,
					 "titulo":"Pilates","idempotencyKey":"k-1"}
					""".formatted(INICIO, INICIO.plus(1, ChronoUnit.HOURS));

			autenticado(post(CLASES + "/ofertas/45").contentType(MediaType.APPLICATION_JSON)
					.content(cuerpo))
					.andExpect(status().isCreated())
					.andExpect(header().string("Location", CLASE))
					.andExpect(jsonPath("$.id").value(CLASE_ID));
			autenticado(post(CLASES + "/ofertas/45").contentType(MediaType.APPLICATION_JSON)
					.content(cuerpo))
					.andExpect(status().isOk())
					.andExpect(header().doesNotExist("Location"));

			ArgumentCaptor<ProgramarClaseCommand> comando =
					ArgumentCaptor.forClass(ProgramarClaseCommand.class);
			verify(claseService, org.mockito.Mockito.times(2))
					.programar(any(), eq(SEDE_ID), eq(45L), comando.capture());
			assertThat(comando.getValue().capacidad()).isEqualTo(8);
			assertThat(comando.getValue().idempotencyKey()).isEqualTo("k-1");
		}

		@Test
		@DisplayName("Una clase de capacidad uno ni siquiera llega al servicio: 400")
		void capacidad_uno_es_400() throws Exception {
			autenticado(post(CLASES + "/ofertas/45").contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"inicio":"%s","fin":"%s","capacidad":1}
							""".formatted(INICIO, INICIO.plus(1, ChronoUnit.HOURS))))
					.andExpect(status().isBadRequest());

			verify(claseService, never()).programar(any(), anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("Reprogramar lleva la version leida al servicio")
		void reprogramar_lleva_la_version() throws Exception {
			given(claseService.reprogramar(any(), anyLong(), anyLong(), any())).willReturn(clase());

			autenticado(post(CLASE + "/reprogramacion").contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"inicio":"%s","fin":"%s","capacidad":10,"version":3}
							""".formatted(INICIO, INICIO.plus(1, ChronoUnit.HOURS))))
					.andExpect(status().isOk());

			ArgumentCaptor<ReprogramarClaseCommand> comando =
					ArgumentCaptor.forClass(ReprogramarClaseCommand.class);
			verify(claseService).reprogramar(any(), eq(SEDE_ID), eq(CLASE_ID), comando.capture());
			assertThat(comando.getValue().version()).isEqualTo(3L);
			assertThat(comando.getValue().capacidad()).isEqualTo(10);
		}

		@Test
		@DisplayName("Cancelar sin motivo es 400; con motivo llega tal cual")
		void cancelar_exige_motivo() throws Exception {
			autenticado(post(CLASE + "/cancelacion").contentType(MediaType.APPLICATION_JSON)
					.content("{\"motivo\":\" \"}"))
					.andExpect(status().isBadRequest());

			given(claseService.cancelar(any(), anyLong(), anyLong(), anyString())).willReturn(clase());
			autenticado(post(CLASE + "/cancelacion").contentType(MediaType.APPLICATION_JSON)
					.content("{\"motivo\":\"El instructor se reporto enfermo\"}"))
					.andExpect(status().isOk());
			verify(claseService).cancelar(any(), eq(SEDE_ID), eq(CLASE_ID),
					eq("El instructor se reporto enfermo"));
		}

		@Test
		@DisplayName("Listar pasa la ventana y el historial publica las transiciones")
		void listar_e_historial() throws Exception {
			Instant desde = INICIO.truncatedTo(ChronoUnit.DAYS);
			Instant hasta = desde.plus(1, ChronoUnit.DAYS);
			given(claseService.listar(any(), anyLong(), any(), any())).willReturn(List.of(clase()));
			given(claseService.historial(any(), anyLong(), anyLong())).willReturn(List.of(
					new EventoDeClaseView(1L, "PROGRAMACION", null, "PROGRAMADA", null, null, null,
							INICIO, INICIO.plus(1, ChronoUnit.HOURS), null, 8, CUENTA, INICIO)));

			autenticado(get(CLASES + "?desde=" + desde + "&hasta=" + hasta))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[0].estado").value("PROGRAMADA"));
			autenticado(get(CLASE + "/historial"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[0].estadoNuevo").value("PROGRAMADA"));

			verify(claseService).listar(any(), eq(SEDE_ID), eq(desde), eq(hasta));
		}

		@Test
		@DisplayName("Una clase de otra sede o de otro tenant es 404 con not-found")
		void clase_ajena_es_404() throws Exception {
			given(claseService.ver(any(), anyLong(), anyLong()))
					.willThrow(new ClaseNotAccessibleException(CLASE_ID));

			autenticado(get(CLASE))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.type").value(ProblemType.NOT_FOUND.uri().toString()));
		}
	}

	// =================================================================================
	// Inscripciones
	// =================================================================================

	@Nested
	@DisplayName("Inscripciones")
	class Inscripciones {

		@Test
		@DisplayName("Inscribir devuelve 201 con Location; el reintento con la misma clave, 200")
		void inscribir_201_y_200() throws Exception {
			given(inscripcionService.inscribir(any(), anyLong(), anyLong(), any()))
					.willReturn(new ResultadoDeInscripcion(inscripcion("RESERVADA"), cupos(), true))
					.willReturn(new ResultadoDeInscripcion(inscripcion("RESERVADA"), cupos(), false));
			String cuerpo = "{\"personaId\":512,\"aceptaListaEspera\":true,\"idempotencyKey\":\"k\"}";

			autenticado(post(CLASE + "/inscripciones").contentType(MediaType.APPLICATION_JSON)
					.content(cuerpo))
					.andExpect(status().isCreated())
					.andExpect(header().string("Location", CLASE + "/inscripciones/301"))
					.andExpect(jsonPath("$.inscripcion.estado").value("RESERVADA"))
					.andExpect(jsonPath("$.cupos.disponibles").value(2));
			autenticado(post(CLASE + "/inscripciones").contentType(MediaType.APPLICATION_JSON)
					.content(cuerpo))
					.andExpect(status().isOk());

			ArgumentCaptor<InscribirCommand> comando = ArgumentCaptor.forClass(InscribirCommand.class);
			verify(inscripcionService, org.mockito.Mockito.times(2))
					.inscribir(any(), eq(SEDE_ID), eq(CLASE_ID), comando.capture());
			assertThat(comando.getValue()).isEqualTo(new InscribirCommand(512L, true, "k"));
		}

		/** El 409 lleva los dos numeros para que la pantalla ofrezca la espera sin otra vuelta. */
		@Test
		@DisplayName("Sin lugar y sin aceptar la espera es 409 clase-completa")
		void clase_completa_es_409() throws Exception {
			given(inscripcionService.inscribir(any(), anyLong(), anyLong(), any()))
					.willThrow(new ClaseCompletaException(CLASE_ID, 8, 8));

			autenticado(post(CLASE + "/inscripciones").contentType(MediaType.APPLICATION_JSON)
					.content("{\"personaId\":512,\"aceptaListaEspera\":false}"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type")
							.value(ProblemType.CLASE_COMPLETA.uri().toString()));
		}

		@Test
		@DisplayName("Confirmar y cancelar devuelven la inscripcion y, al cancelar, a quien se promovio")
		void confirmar_y_cancelar() throws Exception {
			given(inscripcionService.confirmar(any(), anyLong(), anyLong(), anyLong()))
					.willReturn(inscripcion("CONFIRMADA"));
			given(inscripcionService.cancelar(any(), anyLong(), anyLong(), anyLong(), anyString()))
					.willReturn(new ResultadoDeCancelacion(
							inscripcion("CANCELADA"), inscripcion("RESERVADA"), cupos()));

			autenticado(post(CLASE + "/inscripciones/301/confirmacion"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.estado").value("CONFIRMADA"));
			autenticado(post(CLASE + "/inscripciones/301/cancelacion")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"motivo\":\"La paciente aviso que no viene\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.inscripcion.estado").value("CANCELADA"))
					.andExpect(jsonPath("$.promovida.estado").value("RESERVADA"));
		}

		@Test
		@DisplayName("Participantes y cupos se leen por separado")
		void participantes_y_cupos() throws Exception {
			given(inscripcionService.participantes(any(), anyLong(), anyLong())).willReturn(List.of(
					new ParticipanteView(inscripcion("RESERVADA"), "Perez", "Ana", "DNI", "30111222")));
			given(inscripcionService.cupos(any(), anyLong(), anyLong())).willReturn(cupos());

			autenticado(get(CLASE + "/inscripciones"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[0].apellido").value("Perez"));
			autenticado(get(CLASE + "/cupos"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.capacidadEfectiva").value(8));
		}
	}

	// =================================================================================
	// Asistencia
	// =================================================================================

	@Nested
	@DisplayName("Asistencia")
	class Asistencia {

		/**
		 * El origen lo pone el endpoint: si viajara en el cuerpo, cualquier cliente podria marcar
		 * una asistencia como si la hubiera generado el cierre.
		 */
		@Test
		@DisplayName("Marcar es 201 si registro y 200 si no cambio nada; el origen es MOSTRADOR")
		void registrar_201_y_200() throws Exception {
			given(asistenciaService.registrar(any(), anyLong(), anyLong(), any()))
					.willReturn(resultadoAsistencia(true))
					.willReturn(resultadoAsistencia(false));
			String cuerpo = "{\"inscripcionId\":301,\"resultado\":\"PRESENTE_TARDE\"}";

			autenticado(post(CLASE + "/asistencias").contentType(MediaType.APPLICATION_JSON)
					.content(cuerpo))
					.andExpect(status().isCreated())
					.andExpect(header().string("Location", CLASE + "/asistencias/981"));
			autenticado(post(CLASE + "/asistencias").contentType(MediaType.APPLICATION_JSON)
					.content(cuerpo))
					.andExpect(status().isOk());

			ArgumentCaptor<RegistrarAsistenciaCommand> comando =
					ArgumentCaptor.forClass(RegistrarAsistenciaCommand.class);
			verify(asistenciaService, org.mockito.Mockito.times(2))
					.registrar(any(), eq(SEDE_ID), eq(CLASE_ID), comando.capture());
			assertThat(comando.getValue().origen()).isEqualTo(OrigenAsistencia.MOSTRADOR);
			assertThat(comando.getValue().resultado()).isEqualTo(ResultadoAsistencia.PRESENTE_TARDE);
		}

		@Test
		@DisplayName("El lote es 200 aun con fallos parciales y cada item viaja con origen LOTE")
		void lote_200_con_fallos() throws Exception {
			given(asistenciaService.registrarLote(any(), anyLong(), anyLong(), any()))
					.willReturn(ResultadoDeLote.de(List.of(
							ItemDeLote.ok(301L, resultadoAsistencia(true)),
							ItemDeLote.error(302L, ProblemType.NOT_FOUND.value(), "No existe")),
							cupos()));

			autenticado(post(CLASE + "/asistencias/lote").contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"items":[{"inscripcionId":301,"resultado":"PRESENTE"},
							          {"inscripcionId":302,"resultado":"AUSENTE"}]}
							"""))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.exitosos").value(1))
					.andExpect(jsonPath("$.fallidos").value(1))
					.andExpect(jsonPath("$.items[1].problemType")
							.value(ProblemType.NOT_FOUND.uri().toString()));

			@SuppressWarnings("unchecked")
			ArgumentCaptor<List<RegistrarAsistenciaCommand>> comandos =
					ArgumentCaptor.forClass(List.class);
			verify(asistenciaService).registrarLote(any(), eq(SEDE_ID), eq(CLASE_ID),
					comandos.capture());
			assertThat(comandos.getValue()).extracting(RegistrarAsistenciaCommand::origen)
					.containsOnly(OrigenAsistencia.LOTE);
		}

		@Test
		@DisplayName("Un lote vacio no llega al servicio: 400")
		void lote_vacio_es_400() throws Exception {
			autenticado(post(CLASE + "/asistencias/lote").contentType(MediaType.APPLICATION_JSON)
					.content("{\"items\":[]}"))
					.andExpect(status().isBadRequest());

			verify(asistenciaService, never()).registrarLote(any(), anyLong(), anyLong(), any());
		}

		@Test
		@DisplayName("El ingreso sin inscripcion siempre crea: 201 con Location")
		void ingreso_201() throws Exception {
			given(asistenciaService.registrarIngresoSinInscripcion(any(), anyLong(), anyLong(), any()))
					.willReturn(resultadoAsistencia(true));

			autenticado(post(CLASE + "/ingresos").contentType(MediaType.APPLICATION_JSON)
					.content("{\"personaId\":512,\"resultado\":\"PRESENTE\"}"))
					.andExpect(status().isCreated())
					.andExpect(header().string("Location", CLASE + "/asistencias/981"));

			ArgumentCaptor<IngresoSinInscripcionCommand> comando =
					ArgumentCaptor.forClass(IngresoSinInscripcionCommand.class);
			verify(asistenciaService).registrarIngresoSinInscripcion(any(), eq(SEDE_ID),
					eq(CLASE_ID), comando.capture());
			assertThat(comando.getValue().personaId()).isEqualTo(512L);
		}

		/** Una pagina negativa o de mil filas no es un pedido: es un cliente roto. */
		@Test
		@DisplayName("El detalle operativo recorta pagina y tamano antes de llamar al servicio")
		void detalle_recorta_la_paginacion() throws Exception {
			given(asistenciaService.detalleOperativo(any(), anyLong(), anyLong(), anyInt(), anyInt()))
					.willReturn(detalle());

			autenticado(get(CLASE + "/detalle-operativo?page=-3&size=5000"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.titulo").value("Pilates"));
			autenticado(get(CLASE + "/detalle-operativo?size=0")).andExpect(status().isOk());
			autenticado(get(CLASE + "/detalle-operativo")).andExpect(status().isOk());

			verify(asistenciaService).detalleOperativo(any(), eq(SEDE_ID), eq(CLASE_ID), eq(0), eq(100));
			verify(asistenciaService).detalleOperativo(any(), eq(SEDE_ID), eq(CLASE_ID), eq(0), eq(1));
			verify(asistenciaService).detalleOperativo(any(), eq(SEDE_ID), eq(CLASE_ID), eq(0), eq(20));
		}

		@Test
		@DisplayName("Iniciar, cerrar e historial devuelven lo que el servicio resolvio")
		void iniciar_cerrar_historial() throws Exception {
			given(asistenciaService.iniciar(any(), anyLong(), anyLong())).willReturn(clase());
			given(asistenciaService.cerrar(any(), anyLong(), anyLong())).willReturn(
					new ResultadoDeCierre(clase(), true, List.of(asistencia()), 2, cupos()));
			given(asistenciaService.historial(any(), anyLong(), anyLong(), anyLong()))
					.willReturn(List.of(new AsistenciaEventoView(
							1L, "REGISTRO", null, "PRESENTE", null, CUENTA, INICIO)));

			autenticado(post(CLASE + "/inicio")).andExpect(status().isOk());
			autenticado(post(CLASE + "/cierre"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.cerroAhora").value(true))
					.andExpect(jsonPath("$.esperaCancelada").value(2))
					.andExpect(jsonPath("$.ausentados[0].resultado").value("PRESENTE"));
			autenticado(get(CLASE + "/asistencias/981/historial"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[0].resultadoNuevo").value("PRESENTE"));
		}
	}

	// =================================================================================
	// Helpers y fixtures
	// =================================================================================

	private ResultActions autenticado(RequestBuilder peticion) throws Exception {
		return mockMvc.perform(((MockHttpServletRequestBuilder) peticion).with(miembro()));
	}

	private static RequestPostProcessor miembro() {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(CUENTA), "n/a", List.of()));
	}

	private static ClaseView clase() {
		return new ClaseView(CLASE_ID, SEDE_ID, 45L, "Pilates", INICIO,
				INICIO.plus(1, ChronoUnit.HOURS), "PROGRAMADA", 31L, 8L, 8, 8, 6, null, null, 0L);
	}

	private static CuposView cupos() {
		return CuposView.de(CLASE_ID, 8, 8, 6, 0);
	}

	private static InscripcionView inscripcion(String estado) {
		return new InscripcionView(301L, CLASE_ID, 512L, estado, null, INICIO, null, null, null,
				null, 0L);
	}

	private static AsistenciaView asistencia() {
		return new AsistenciaView(981L, CLASE_ID, 512L, 301L, "PRESENTE", "MOSTRADOR", 31L, null,
				INICIO, null, 0, 0L);
	}

	private static ResultadoDeAsistencia resultadoAsistencia(boolean registrada) {
		return new ResultadoDeAsistencia(asistencia(), inscripcion("ASISTIO"), registrada, false);
	}

	private static DetalleOperativoView detalle() {
		return new DetalleOperativoView(CLASE_ID, 45L, "Pilates", INICIO,
				INICIO.plus(1, ChronoUnit.HOURS), "EN_CURSO", INICIO, null, 31L, 8, 8, 6, 0, 1, 0, 5,
				List.of(), 0, 20, 6);
	}

	/** Principal minimo: el slice no evalua permisos, los evalua el servicio, que esta mockeado. */
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
