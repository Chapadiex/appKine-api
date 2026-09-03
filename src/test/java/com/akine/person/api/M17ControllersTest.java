package com.akine.person.api;

import com.akine.person.application.AutorizacionService;
import com.akine.person.application.AutorizacionView;
import com.akine.person.application.ElegibilidadAdministrativa;
import com.akine.person.application.ElegibilidadAdministrativaService;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.OrdenMedicaService;
import com.akine.person.application.OrdenView;
import com.akine.person.application.RequisitoAdministrativo;
import com.akine.person.application.TipoRequisito;
import com.akine.person.domain.exception.AutorizacionInactivaException;
import com.akine.person.domain.exception.AutorizacionNotAccessibleException;
import com.akine.person.domain.exception.AutorizacionSuperpuestaException;
import com.akine.person.domain.exception.AutorizacionTransicionNoPermitidaException;
import com.akine.person.domain.exception.NumeroDeDocumentoTakenException;
import com.akine.person.domain.exception.OrdenInactivaException;
import com.akine.person.domain.exception.OrdenNotAccessibleException;
import com.akine.person.domain.exception.PersonaSinPerfilPacienteException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * El contrato HTTP de M17: codigos, forma del cuerpo y traduccion de las excepciones de dominio.
 *
 * <p>Slice web: no levanta base ni contexto completo. La autorizacion NO se prueba aca —la cadena
 * del slice deja pasar todo a proposito— porque la decision se toma en la capa de aplicacion.
 *
 * <p>Lo que si se prueba y solo se puede probar aca: que cada excepcion salga con SU {@code type},
 * que el 404 sea indistinguible entre "no existe", "es de otro tenant" y "es de otro paciente",
 * que <b>la elegibilidad responda 200 aunque falten requisitos</b> —el error que 05.01 y 03.05 ya
 * evitaron con sus motivos—, y que el 204 de las bajas responda aunque el cliente pida
 * {@code application/problem+json}: el defecto que rompio la activacion de cuenta en 01.02.
 */
@WebMvcTest({OrdenMedicaController.class, AutorizacionController.class,
		ElegibilidadController.class})
@Import({PersonApiSliceSecurityConfig.class, PersonProblemHandler.class})
@DisplayName("API de ordenes, autorizaciones y elegibilidad")
class M17ControllersTest {

	private static final String TIPO = "https://akine.app/problems/";
	private static final String ORDENES = "/api/v1/personas/1204/ordenes";
	private static final String AUTORIZACIONES = "/api/v1/personas/1204/autorizaciones";
	private static final String ELEGIBILIDAD = "/api/v1/personas/1204/elegibilidad";
	private static final LocalDate ENERO = LocalDate.of(2027, 1, 1);
	private static final LocalDate DICIEMBRE = LocalDate.of(2027, 12, 31);

	private static final String ALTA_ORDEN = """
			{"profesionalEmisor":"Dra. Sintetica","fechaEmision":"2027-01-01",\
			"indicacion":"Kinesiologia","sesionesPrescriptas":10}""";
	private static final String ALTA_AUTORIZACION = """
			{"coberturaId":412,"practicaId":33,"numero":"AUT-1","cantidadAutorizada":10,\
			"vigenciaDesde":"2027-01-01"}""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrdenMedicaService ordenService;

	@MockitoBean
	private AutorizacionService autorizacionService;

	@MockitoBean
	private ElegibilidadAdministrativaService elegibilidadService;

	@MockitoBean
	private PersonApiActor apiActor;

	@BeforeEach
	void setUp() {
		given(apiActor.current()).willReturn(new OperatingActor(1L, false, 7L, 20L));
	}

	// =================================================================================
	// Ordenes
	// =================================================================================

	@Nested
	@DisplayName("Ordenes medicas")
	class DeLasOrdenes {

		@Test
		@DisplayName("el historial publica el vencimiento CALCULADO, y la orden vencida sigue ACTIVA")
		void historial() throws Exception {
			given(ordenService.listar(any(), anyLong(), any(), any()))
					.willReturn(List.of(orden("ACTIVA", true, -30L)));

			mockMvc.perform(get(ORDENES).param("estado", "TODAS").param("fecha", "2028-01-30"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[0].estado").value("ACTIVA"))
					// Vencida NO es dada de baja: "un documento vencido no desaparece".
					.andExpect(jsonPath("$[0].vencida").value(true))
					.andExpect(jsonPath("$[0].diasParaVencer").value(-30));
		}

		@Test
		@DisplayName("el alta responde 201 con Location")
		void alta() throws Exception {
			given(ordenService.registrar(any(), anyLong(), any()))
					.willReturn(orden("ACTIVA", false, 90L));

			mockMvc.perform(post(ORDENES)
							.contentType(MediaType.APPLICATION_JSON)
							.content(ALTA_ORDEN))
					.andExpect(status().isCreated())
					.andExpect(header().string("Location", "/api/v1/personas/1204/ordenes/51"))
					.andExpect(jsonPath("$.profesionalEmisor").value("Dra. Sintetica"));
		}

		@Test
		@DisplayName("sin profesional emisor es 400, no 500")
		void alta_sin_emisor() throws Exception {
			mockMvc.perform(post(ORDENES)
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"fechaEmision\":\"2027-01-01\"}"))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("una persona que no es paciente responde 409 con el type que 03.04 ya declaro")
		void alta_sobre_no_paciente() throws Exception {
			willThrow(new PersonaSinPerfilPacienteException(1204L))
					.given(ordenService).registrar(any(), anyLong(), any());

			mockMvc.perform(post(ORDENES)
							.contentType(MediaType.APPLICATION_JSON)
							.content(ALTA_ORDEN))
					.andExpect(status().isConflict())
					// Misma condicion y misma accion correctiva que en M08: un type propio
					// obligaria al frontend a manejar dos codigos para lo mismo.
					.andExpect(jsonPath("$.type").value(TIPO + "persona-sin-perfil-paciente"));
		}

		@Test
		@DisplayName("un numero de orden repetido responde 409 documento-numero-taken")
		void numero_repetido() throws Exception {
			willThrow(new NumeroDeDocumentoTakenException("orden medica", "OM-1"))
					.given(ordenService).registrar(any(), anyLong(), any());

			mockMvc.perform(post(ORDENES)
							.contentType(MediaType.APPLICATION_JSON)
							.content(ALTA_ORDEN))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "documento-numero-taken"))
					.andExpect(jsonPath("$.numero").value("OM-1"));
		}

		@Test
		@DisplayName("la orden de otro paciente responde 404, indistinguible de no existir")
		void orden_ajena() throws Exception {
			willThrow(new OrdenNotAccessibleException(51L))
					.given(ordenService).editar(any(), anyLong(), anyLong(), any());

			mockMvc.perform(put(ORDENES + "/51")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"expectedVersion\":0}"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.type").value(TIPO + "not-found"));
		}

		@Test
		@DisplayName("editar una orden dada de baja es 409 orden-inactiva")
		void edicion_de_una_baja() throws Exception {
			willThrow(new OrdenInactivaException(51L, "edicion"))
					.given(ordenService).editar(any(), anyLong(), anyLong(), any());

			mockMvc.perform(put(ORDENES + "/51")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"expectedVersion\":0}"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "orden-inactiva"));
		}

		@Test
		@DisplayName("dar de baja dos veces es 409 orden-already-inactive: otro type para otra accion")
		void segunda_baja() throws Exception {
			willThrow(new OrdenInactivaException(51L, "dar de baja"))
					.given(ordenService).darDeBaja(any(), anyLong(), anyLong(), anyString());

			mockMvc.perform(delete(ORDENES + "/51")
							.contentType(MediaType.APPLICATION_JSON)
							.accept(MediaType.APPLICATION_PROBLEM_JSON)
							.content("{\"reason\":\"cargada por error\"}"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "orden-already-inactive"));
		}

		@Test
		@DisplayName("la baja responde 204 aunque el cliente pida problem+json")
		void baja() throws Exception {
			// El defecto que rompio la activacion de cuenta en 01.02: el cliente generado manda
			// Accept: application/problem+json y el produces de clase lo cortaba con 406 antes de
			// entrar al metodo.
			mockMvc.perform(delete(ORDENES + "/51")
							.contentType(MediaType.APPLICATION_JSON)
							.accept(MediaType.APPLICATION_PROBLEM_JSON)
							.content("{\"reason\":\"cargada por error\"}"))
					.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("la baja sin motivo es 400")
		void baja_sin_motivo() throws Exception {
			mockMvc.perform(delete(ORDENES + "/51")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{}"))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("vincular el documento devuelve la orden con el adjunto")
		void vincular_documento() throws Exception {
			given(ordenService.vincularDocumento(any(), anyLong(), anyLong(), any()))
					.willReturn(orden("ACTIVA", false, 90L));

			mockMvc.perform(post(ORDENES + "/51/documento")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"adjuntoId\":907}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.adjuntoId").value(907));
		}
	}

	// =================================================================================
	// Autorizaciones
	// =================================================================================

	@Nested
	@DisplayName("Autorizaciones")
	class DeLasAutorizaciones {

		@Test
		@DisplayName("el listado publica el saldo, y cantidadConsumida vale cero en esta version")
		void listado() throws Exception {
			given(autorizacionService.listar(any(), anyLong(), any(), any()))
					.willReturn(List.of(autorizacion("APROBADA", 10, true)));

			mockMvc.perform(get(AUTORIZACIONES).param("fecha", "2027-06-15"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[0].cantidadAutorizada").value(10))
					// RN-M17-001: autorizado y consumido son dos cosas, y esta etapa no mueve la
					// segunda.
					.andExpect(jsonPath("$[0].cantidadConsumida").value(0))
					.andExpect(jsonPath("$[0].saldo").value(10))
					.andExpect(jsonPath("$[0].habilita").value(true));
		}

		@Test
		@DisplayName("el detalle responde el saldo de RF-M17-003")
		void detalle() throws Exception {
			given(autorizacionService.ver(any(), anyLong(), anyLong(), any()))
					.willReturn(autorizacion("APROBADA", 10, true));

			mockMvc.perform(get(AUTORIZACIONES + "/77"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.numero").value("AUT-1"));
		}

		@Test
		@DisplayName("el alta responde 201 con Location")
		void alta() throws Exception {
			given(autorizacionService.registrar(any(), anyLong(), any()))
					.willReturn(autorizacion("PENDIENTE", 10, false));

			mockMvc.perform(post(AUTORIZACIONES)
							.contentType(MediaType.APPLICATION_JSON)
							.content(ALTA_AUTORIZACION))
					.andExpect(status().isCreated())
					.andExpect(header().string(
							"Location", "/api/v1/personas/1204/autorizaciones/77"))
					.andExpect(jsonPath("$.estadoAutorizacion").value("PENDIENTE"));
		}

		@Test
		@DisplayName("cargar una autorizacion OBSERVADA de entrada es 400: es la respuesta a un pedido")
		void estado_inicial_no_cargable() throws Exception {
			mockMvc.perform(post(AUTORIZACIONES)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"coberturaId":412,"practicaId":33,"numero":"AUT-1",\
									"estadoInicial":"OBSERVADA","vigenciaDesde":"2027-01-01"}"""))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("sin cobertura el alta es 400: una autorizacion sin cobertura no significa nada")
		void alta_sin_cobertura() throws Exception {
			mockMvc.perform(post(AUTORIZACIONES)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"practicaId":33,"numero":"AUT-1",\
									"vigenciaDesde":"2027-01-01"}"""))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("el solapamiento sale con SU type y con el id de la que choca")
		void solapamiento() throws Exception {
			willThrow(new AutorizacionSuperpuestaException(70L))
					.given(autorizacionService).registrar(any(), anyLong(), any());

			mockMvc.perform(post(AUTORIZACIONES)
							.contentType(MediaType.APPLICATION_JSON)
							.content(ALTA_AUTORIZACION))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "autorizacion-superpuesta"))
					// El id de la contraparte es del MISMO paciente, asi que nombrarlo no filtra
					// nada y le permite a la pantalla ofrecer finalizarla.
					.andExpect(jsonPath("$.autorizacionExistenteId").value(70));
		}

		@Test
		@DisplayName("resolver aplica una ACCION, no un estado destino")
		void resolver() throws Exception {
			given(autorizacionService.resolver(any(), anyLong(), anyLong(), any()))
					.willReturn(autorizacion("APROBADA", 6, true));

			mockMvc.perform(post(AUTORIZACIONES + "/77/estado")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"accion":"APROBAR","cantidadAutorizada":6,\
									"expectedVersion":0}"""))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.estadoAutorizacion").value("APROBADA"))
					// La autorizacion parcial: se pidieron 10 y el financiador dio 6.
					.andExpect(jsonPath("$.cantidadAutorizada").value(6));
		}

		@Test
		@DisplayName("una accion desconocida es 400")
		void accion_desconocida() throws Exception {
			mockMvc.perform(post(AUTORIZACIONES + "/77/estado")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"accion\":\"ANULAR\",\"expectedVersion\":0}"))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("resolver una APROBADA es 409 con el estado y la accion en el cuerpo")
		void transicion_terminal() throws Exception {
			willThrow(new AutorizacionTransicionNoPermitidaException(77L, "APROBADA", "APROBAR"))
					.given(autorizacionService).resolver(any(), anyLong(), anyLong(), any());

			mockMvc.perform(post(AUTORIZACIONES + "/77/estado")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"accion\":\"APROBAR\",\"expectedVersion\":0}"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type")
							.value(TIPO + "autorizacion-transicion-no-permitida"))
					.andExpect(jsonPath("$.estadoActual").value("APROBADA"))
					.andExpect(jsonPath("$.accion").value("APROBAR"));
		}

		@Test
		@DisplayName("la autorizacion de otro paciente responde 404")
		void autorizacion_ajena() throws Exception {
			willThrow(new AutorizacionNotAccessibleException(77L))
					.given(autorizacionService).ver(any(), anyLong(), anyLong(), any());

			mockMvc.perform(get(AUTORIZACIONES + "/77"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.type").value(TIPO + "not-found"));
		}

		@Test
		@DisplayName("editar una dada de baja es 409 autorizacion-inactiva")
		void edicion_de_una_baja() throws Exception {
			willThrow(new AutorizacionInactivaException(77L, "edicion"))
					.given(autorizacionService).editar(any(), anyLong(), anyLong(), any());

			mockMvc.perform(put(AUTORIZACIONES + "/77")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"expectedVersion\":0}"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "autorizacion-inactiva"));
		}

		@Test
		@DisplayName("dar de baja dos veces es 409 autorizacion-already-inactive")
		void segunda_baja() throws Exception {
			willThrow(new AutorizacionInactivaException(77L, "dar de baja"))
					.given(autorizacionService).darDeBaja(any(), anyLong(), anyLong(), anyString());

			mockMvc.perform(delete(AUTORIZACIONES + "/77")
							.contentType(MediaType.APPLICATION_JSON)
							.accept(MediaType.APPLICATION_PROBLEM_JSON)
							.content("{\"reason\":\"cargada por error\"}"))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "autorizacion-already-inactive"));
		}

		@Test
		@DisplayName("la baja responde 204 aunque el cliente pida problem+json")
		void baja() throws Exception {
			mockMvc.perform(delete(AUTORIZACIONES + "/77")
							.contentType(MediaType.APPLICATION_JSON)
							.accept(MediaType.APPLICATION_PROBLEM_JSON)
							.content("{\"reason\":\"cargada por error\"}"))
					.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("vincular el comprobante devuelve la autorizacion")
		void vincular_documento() throws Exception {
			given(autorizacionService.vincularDocumento(any(), anyLong(), anyLong(), any()))
					.willReturn(autorizacion("APROBADA", 10, true));

			mockMvc.perform(post(AUTORIZACIONES + "/77/documento")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"adjuntoId\":908}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.adjuntoId").value(908));
		}
	}

	// =================================================================================
	// Elegibilidad
	// =================================================================================

	@Nested
	@DisplayName("Elegibilidad administrativa")
	class DeLaElegibilidad {

		@Test
		@DisplayName("un requisito faltante responde 200, no 409: es informacion, no un error")
		void requisito_faltante_es_200() throws Exception {
			given(elegibilidadService.consultar(any(), anyLong(), anyLong(), anyLong(), any()))
					.willReturn(ElegibilidadAdministrativa.evaluada(
							LocalDate.of(2027, 6, 15),
							List.of(RequisitoAdministrativo.faltante(
									TipoRequisito.ORDEN, "Falta la orden medica.")),
							12L, "Convenio Sintetico", 20));

			mockMvc.perform(get(ELEGIBILIDAD)
							.param("coberturaId", "412")
							.param("practicaId", "33")
							.param("fecha", "2027-06-15"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.elegible").value(false))
					.andExpect(jsonPath("$.requisitos[0].tipo").value("ORDEN"))
					.andExpect(jsonPath("$.requisitos[0].cumplido").value(false))
					// El tope viaja INFORMATIVO: nadie lo verifica hasta que exista el consumo.
					.andExpect(jsonPath("$.limiteSesionesMensual").value(20));
		}

		@Test
		@DisplayName("sin convenio la lista viene vacia, elegible en true y el motivo lo explica")
		void sin_convenio_es_elegible() throws Exception {
			given(elegibilidadService.consultar(any(), anyLong(), anyLong(), anyLong(), any()))
					.willReturn(ElegibilidadAdministrativa.sinRequisitos(
							LocalDate.of(2027, 6, 15), "SIN_CONVENIO_VIGENTE"));

			mockMvc.perform(get(ELEGIBILIDAD)
							.param("coberturaId", "412")
							.param("practicaId", "33"))
					.andExpect(status().isOk())
					// RN-M17-006: una actividad no cubierta no pide orden ni autorizacion.
					.andExpect(jsonPath("$.elegible").value(true))
					.andExpect(jsonPath("$.requisitos").isEmpty())
					.andExpect(jsonPath("$.motivo").value("SIN_CONVENIO_VIGENTE"));
		}

		@Test
		@DisplayName("un requisito cumplido publica la referencia y el saldo")
		void requisito_cumplido() throws Exception {
			given(elegibilidadService.consultar(any(), anyLong(), anyLong(), anyLong(), any()))
					.willReturn(ElegibilidadAdministrativa.evaluada(
							LocalDate.of(2027, 6, 15),
							List.of(RequisitoAdministrativo.cumplido(
									TipoRequisito.AUTORIZACION, 77L, "Autorizacion AUT-1.", 6)),
							12L, "Convenio Sintetico", null));

			mockMvc.perform(get(ELEGIBILIDAD)
							.param("coberturaId", "412")
							.param("practicaId", "33"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.elegible").value(true))
					.andExpect(jsonPath("$.requisitos[0].referenciaId").value(77))
					.andExpect(jsonPath("$.requisitos[0].saldo").value(6));
		}

		@Test
		@DisplayName("sin coberturaId ni practicaId es 400: son obligatorios")
		void parametros_obligatorios() throws Exception {
			mockMvc.perform(get(ELEGIBILIDAD))
					.andExpect(status().isBadRequest());
		}
	}

	// =================================================================================
	// Fixture
	// =================================================================================

	private static OrdenView orden(String estado, boolean vencida, Long diasParaVencer) {
		return new OrdenView(
				51L, 1204L, 20L, 412L, "OM-1", "Dra. Sintetica", "MP 1", ENERO, "Kinesiologia",
				10, ENERO, DICIEMBRE, !vencida, vencida, diasParaVencer, 907L, null,
				estado, null, null, 0L);
	}

	private static AutorizacionView autorizacion(
			String estadoAutorizacion, Integer cantidad, boolean habilita) {

		return new AutorizacionView(
				77L, 1204L, 20L, 412L, 51L, 33L, "AUT-1", estadoAutorizacion, null,
				cantidad, 0, cantidad, ENERO, DICIEMBRE, true, false, false, habilita, 199L,
				12L, "CONV-1", "Convenio Sintetico", true, true, false, null, 908L, null,
				"ACTIVA", null, null, 0L);
	}
}
