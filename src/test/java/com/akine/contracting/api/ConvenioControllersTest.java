package com.akine.contracting.api;

import com.akine.contracting.application.ArancelService;
import com.akine.contracting.application.ArancelView;
import com.akine.contracting.application.ConvenioService;
import com.akine.contracting.application.ConvenioView;
import com.akine.contracting.application.OperatingActor;
import com.akine.contracting.domain.exception.ArancelNotAccessibleException;
import com.akine.contracting.domain.exception.ArancelSolapadoException;
import com.akine.contracting.domain.exception.ArancelYaInactivoException;
import com.akine.contracting.domain.exception.ConvenioCodigoTakenException;
import com.akine.contracting.domain.exception.ConvenioNotAccessibleException;
import com.akine.contracting.domain.exception.ConvenioSolapadoException;
import com.akine.contracting.domain.exception.ConvenioYaInactivoException;
import com.akine.contracting.domain.exception.PracticaNoAccesibleException;
import com.akine.contracting.domain.exception.SedeNoAccesibleException;
import com.akine.contracting.spi.ArancelVigente;
import com.akine.contracting.spi.MotivoSinArancel;
import com.akine.contracting.spi.ResolucionDeArancel;
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

import java.math.BigDecimal;
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
 * El contrato HTTP de M16: codigos, forma del cuerpo y traduccion de las excepciones de dominio.
 *
 * <p>Slice web: no levanta base de datos ni contexto completo. La autorizacion NO se prueba aca —la
 * cadena del slice deja pasar todo a proposito— porque la decision se toma en la capa de aplicacion
 * y la cubren {@code ConvenioServiceTest} y {@code ArancelServiceTest}.
 *
 * <p>Lo que si se prueba y solo se puede probar aca: que el 409 de solapamiento salga con SU
 * {@code type} y con el id del convenio que choca en el cuerpo, que el 404 sea indistinguible entre
 * "no existe" y "es de otra sede", que el resolutor responda <b>200 cuando no hay arancel</b>, y
 * que el 204 de las bajas responda aunque el cliente pida {@code application/problem+json} — el
 * defecto que rompio la activacion de cuenta y que este slice existe, en parte, para no repetir.
 */
@WebMvcTest({ConvenioController.class, ArancelController.class})
@Import({ContractingApiSliceSecurityConfig.class, ContractingProblemHandler.class})
@DisplayName("API de convenios y aranceles")
class ConvenioControllersTest {

	private static final String TIPO = "https://akine.app/problems/";
	private static final String CONVENIOS = "/api/v1/consultorios/20/convenios";
	private static final String ARANCELES = CONVENIOS + "/140/aranceles";
	private static final LocalDate ENERO = LocalDate.of(2026, 1, 1);
	private static final LocalDate DICIEMBRE = LocalDate.of(2026, 12, 31);

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ConvenioService convenioService;

	@MockitoBean
	private ArancelService arancelService;

	@MockitoBean
	private ContractingApiActor apiActor;

	@BeforeEach
	void setUp() {
		given(apiActor.current()).willReturn(new OperatingActor(1L, false, 7L, 20L));
	}

	// =================================================================================
	// Convenios
	// =================================================================================

	@Nested
	@DisplayName("Convenios")
	class Convenios {

		@Test
		@DisplayName("el listado publica el codigo, el estado y la vigencia por separado")
		void listado() throws Exception {
			given(convenioService.listar(any(), anyLong(), any(), any()))
					.willReturn(List.of(convenio("ACTIVO", false)));

			mockMvc.perform(get(CONVENIOS).param("fecha", "2027-01-01"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[0].codigo").value("OSDE-210"))
					.andExpect(jsonPath("$[0].estado").value("ACTIVO"))
					.andExpect(jsonPath("$[0].vigente").value(false))
					.andExpect(jsonPath("$[0].modalidad").value("POR_PRESTACION"));
		}

		@Test
		@DisplayName("un convenio INACTIVO se lee con 200, no con 404")
		void inactivo_se_lee_con_200() throws Exception {
			// Lo que ya se liquido bajo el tiene que seguir siendo explicable (§38).
			given(convenioService.ver(any(), anyLong(), anyLong(), any()))
					.willReturn(convenio("INACTIVO", true));

			mockMvc.perform(get(CONVENIOS + "/140"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.estado").value("INACTIVO"));
		}

		@Test
		@DisplayName("el alta devuelve 201 con Location")
		void alta() throws Exception {
			given(convenioService.crear(any(), anyLong(), any()))
					.willReturn(convenio("ACTIVO", true));

			mockMvc.perform(post(CONVENIOS)
							.contentType(MediaType.APPLICATION_JSON)
							.content(cuerpoDeAlta()))
					.andExpect(status().isCreated())
					.andExpect(header().string("Location", CONVENIOS + "/140"))
					.andExpect(jsonPath("$.id").value(140));
		}

		@Test
		@DisplayName("EL 409 DE LA ETAPA: convenio-solapado, con el id y el periodo que choca")
		void solapado() throws Exception {
			// Un 409 que solo dice "se solapa" obliga al administrador a buscar a mano cual de los
			// suyos es.
			willThrow(new ConvenioSolapadoException(999L, "2026-01-01 a sin fin previsto"))
					.given(convenioService).crear(any(), anyLong(), any());

			mockMvc.perform(post(CONVENIOS)
							.contentType(MediaType.APPLICATION_JSON)
							.content(cuerpoDeAlta()))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "convenio-solapado"))
					.andExpect(jsonPath("$.convenioExistenteId").value(999))
					.andExpect(jsonPath("$.periodoExistente")
							.value("2026-01-01 a sin fin previsto"));
		}

		@Test
		@DisplayName("el codigo repetido sale con su propio type")
		void codigo_repetido() throws Exception {
			willThrow(new ConvenioCodigoTakenException("OSDE-210"))
					.given(convenioService).crear(any(), anyLong(), any());

			mockMvc.perform(post(CONVENIOS)
							.contentType(MediaType.APPLICATION_JSON)
							.content(cuerpoDeAlta()))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "convenio-codigo-taken"));
		}

		@Test
		@DisplayName("una sede ajena y un convenio de otra sede responden el MISMO 404")
		void los_404_son_indistinguibles() throws Exception {
			willThrow(new SedeNoAccesibleException(21L))
					.given(convenioService).listar(any(), anyLong(), any(), any());
			willThrow(new ConvenioNotAccessibleException(140L))
					.given(convenioService).ver(any(), anyLong(), anyLong(), any());

			mockMvc.perform(get("/api/v1/consultorios/21/convenios"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.type").value(TIPO + "not-found"));
			mockMvc.perform(get(CONVENIOS + "/140"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.type").value(TIPO + "not-found"));
		}

		@Test
		@DisplayName("editar devuelve 200 y el PUT es el que cierra la vigencia")
		void edicion() throws Exception {
			given(convenioService.editar(any(), anyLong(), anyLong(), any()))
					.willReturn(convenio("ACTIVO", true));

			mockMvc.perform(put(CONVENIOS + "/140")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"vigenciaHasta":"2026-06-30","expectedVersion":0}"""))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.id").value(140));
		}

		@Test
		@DisplayName("un convenio dado de baja no admite ediciones: convenio-inactivo")
		void edicion_de_uno_inactivo() throws Exception {
			willThrow(new ConvenioYaInactivoException(140L, "editar"))
					.given(convenioService).editar(any(), anyLong(), anyLong(), any());

			mockMvc.perform(put(CONVENIOS + "/140")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"nombre":"Otro","expectedVersion":0}"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "convenio-inactivo"));
		}

		@Test
		@DisplayName("la baja responde 204 AUNQUE el cliente pida application/problem+json")
		void baja_con_accept_problem_json() throws Exception {
			// Es el defecto que rompio la activacion de cuenta hasta be14ba5: una operacion 204
			// cuyo produces no incluye problem+json se corta con 406 ANTES de entrar al metodo
			// cuando el cliente generado manda ese Accept. Ningun unitario con
			// HttpTestingController lo agarra, porque no negocia contenido.
			mockMvc.perform(delete(CONVENIOS + "/140")
							.contentType(MediaType.APPLICATION_JSON)
							.accept(MediaType.APPLICATION_PROBLEM_JSON)
							.content("""
									{"reason":"El centro dejo de trabajar con este plan"}"""))
					.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("dar de baja dos veces es convenio-already-inactive, distinto de -inactivo")
		void baja_dos_veces() throws Exception {
			willThrow(new ConvenioYaInactivoException(140L, "dar de baja"))
					.given(convenioService).darDeBaja(any(), anyLong(), anyLong(), anyString());

			mockMvc.perform(delete(CONVENIOS + "/140")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"reason":"otra vez"}"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "convenio-already-inactive"));
		}

		@Test
		@DisplayName("la baja sin motivo es 400")
		void baja_sin_motivo() throws Exception {
			mockMvc.perform(delete(CONVENIOS + "/140")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"reason":"  "}"""))
					.andExpect(status().isBadRequest());
		}
	}

	// =================================================================================
	// Aranceles
	// =================================================================================

	@Nested
	@DisplayName("Aranceles")
	class Aranceles {

		@Test
		@DisplayName("el listado publica los tres importes y la moneda")
		void listado() throws Exception {
			given(arancelService.listar(any(), anyLong(), anyLong(), any(), any()))
					.willReturn(List.of(arancel()));

			mockMvc.perform(get(ARANCELES))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[0].importeTotal").value(12000.00))
					.andExpect(jsonPath("$[0].importeFinanciador").value(9600.00))
					.andExpect(jsonPath("$[0].coseguro").value(2400.00))
					.andExpect(jsonPath("$[0].moneda").value("ARS"));
		}

		@Test
		@DisplayName("el alta devuelve 201 con Location")
		void alta() throws Exception {
			given(arancelService.crear(any(), anyLong(), anyLong(), any())).willReturn(arancel());

			mockMvc.perform(post(ARANCELES)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"practicaId":412,"importeTotal":12000.00,
									 "importeFinanciador":9600.00,"coseguro":2400.00,
									 "vigenciaDesde":"2026-01-01"}"""))
					.andExpect(status().isCreated())
					.andExpect(header().string("Location", ARANCELES + "/901"));
		}

		@Test
		@DisplayName("un arancel solapado sale con su type y el id del que choca")
		void solapado() throws Exception {
			willThrow(new ArancelSolapadoException(888L, "2026-01-01 a 2026-06-30"))
					.given(arancelService).crear(any(), anyLong(), anyLong(), any());

			mockMvc.perform(post(ARANCELES)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"practicaId":412,"importeTotal":1.00,
									 "importeFinanciador":1.00,"coseguro":0.00,
									 "vigenciaDesde":"2026-01-01"}"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "arancel-solapado"))
					.andExpect(jsonPath("$.arancelExistenteId").value(888));
		}

		@Test
		@DisplayName("una practica de otro tenant es 404, no 400")
		void practica_ajena() throws Exception {
			willThrow(new PracticaNoAccesibleException(412L))
					.given(arancelService).crear(any(), anyLong(), anyLong(), any());

			mockMvc.perform(post(ARANCELES)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"practicaId":412,"importeTotal":1.00,
									 "importeFinanciador":1.00,"coseguro":0.00,
									 "vigenciaDesde":"2026-01-01"}"""))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.type").value(TIPO + "not-found"));
		}

		@Test
		@DisplayName("un importe negativo no llega al servicio: lo corta la validacion con 400")
		void importe_negativo() throws Exception {
			mockMvc.perform(post(ARANCELES)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"practicaId":412,"importeTotal":-1.00,
									 "importeFinanciador":-1.00,"coseguro":0.00,
									 "vigenciaDesde":"2026-01-01"}"""))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("editar devuelve 200")
		void edicion() throws Exception {
			given(arancelService.editar(any(), anyLong(), anyLong(), anyLong(), any()))
					.willReturn(arancel());

			mockMvc.perform(put(ARANCELES + "/901")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"importeTotal":13000.00,"importeFinanciador":13000.00,
									 "coseguro":0.00,"expectedVersion":0}"""))
					.andExpect(status().isOk());
		}

		@Test
		@DisplayName("un arancel de otro convenio es 404")
		void arancel_ajeno() throws Exception {
			willThrow(new ArancelNotAccessibleException(901L))
					.given(arancelService).editar(any(), anyLong(), anyLong(), anyLong(), any());

			mockMvc.perform(put(ARANCELES + "/901")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"expectedVersion":0}"""))
					.andExpect(status().isNotFound());
		}

		@Test
		@DisplayName("la baja responde 204 aunque el cliente pida problem+json")
		void baja() throws Exception {
			mockMvc.perform(delete(ARANCELES + "/901")
							.contentType(MediaType.APPLICATION_JSON)
							.accept(MediaType.APPLICATION_PROBLEM_JSON)
							.content("""
									{"reason":"Renegociado"}"""))
					.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("dar de baja dos veces es arancel-already-inactive")
		void baja_dos_veces() throws Exception {
			willThrow(new ArancelYaInactivoException(901L, "dar de baja"))
					.given(arancelService)
					.darDeBaja(any(), anyLong(), anyLong(), anyLong(), anyString());

			mockMvc.perform(delete(ARANCELES + "/901")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"reason":"otra vez"}"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "arancel-already-inactive"));
		}

		@Test
		@DisplayName("editar uno dado de baja es arancel-inactivo")
		void edicion_de_uno_inactivo() throws Exception {
			willThrow(new ArancelYaInactivoException(901L, "editar"))
					.given(arancelService).editar(any(), anyLong(), anyLong(), anyLong(), any());

			mockMvc.perform(put(ARANCELES + "/901")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"expectedVersion":0}"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "arancel-inactivo"));
		}
	}

	// =================================================================================
	// Resolucion
	// =================================================================================

	@Nested
	@DisplayName("Arancel efectivo")
	class Efectivo {

		@Test
		@DisplayName("cuando resuelve, devuelve el importe Y la derivacion completa")
		void resuelve() throws Exception {
			given(arancelService.resolver(any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
					.willReturn(ResolucionDeArancel.resuelta(vigente()));

			mockMvc.perform(get("/api/v1/consultorios/20/aranceles/efectivo")
							.param("financiadorId", "31")
							.param("planId", "88")
							.param("practicaId", "412")
							.param("fecha", "2026-03-15"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.resuelto").value(true))
					.andExpect(jsonPath("$.importeTotal").value(12000.00))
					.andExpect(jsonPath("$.convenioCodigo").value("OSDE-210"))
					.andExpect(jsonPath("$.arancelVigenciaHasta").value("2026-12-31"))
					.andExpect(jsonPath("$.motivo").doesNotExist());
		}

		@Test
		@DisplayName("SIN CONVENIO RESPONDE 200, no 404, y dice por que")
		void sin_convenio_es_200() throws Exception {
			// Es el desenlace mas frecuente —el paciente se atiende como particular— y un 404
			// obligaria a la pantalla a tratar el caso normal como una excepcion. RN-M16-005.
			given(arancelService.resolver(any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
					.willReturn(ResolucionDeArancel.sinArancel(
							MotivoSinArancel.SIN_CONVENIO_VIGENTE));

			mockMvc.perform(get("/api/v1/consultorios/20/aranceles/efectivo")
							.param("financiadorId", "31")
							.param("planId", "88")
							.param("practicaId", "412"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.resuelto").value(false))
					.andExpect(jsonPath("$.motivo").value("SIN_CONVENIO_VIGENTE"))
					.andExpect(jsonPath("$.importeTotal").doesNotExist());
		}

		@Test
		@DisplayName("'hay convenio pero la practica no esta tarifada' es un motivo DISTINTO")
		void sin_arancel_es_otro_motivo() throws Exception {
			given(arancelService.resolver(any(), anyLong(), anyLong(), anyLong(), anyLong(), any(), any()))
					.willReturn(ResolucionDeArancel.sinArancel(MotivoSinArancel.SIN_ARANCEL_VIGENTE));

			mockMvc.perform(get("/api/v1/consultorios/20/aranceles/efectivo")
							.param("financiadorId", "31")
							.param("planId", "88")
							.param("practicaId", "412"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.motivo").value("SIN_ARANCEL_VIGENTE"));
		}
	}

	// =================================================================================
	// Fixtures
	// =================================================================================

	private static String cuerpoDeAlta() {
		return """
				{"financiadorId":31,"planId":88,"codigo":"OSDE-210","nombre":"OSDE 210",
				 "modalidad":"POR_PRESTACION","vigenciaDesde":"2026-01-01","moneda":"ARS"}""";
	}

	private static ConvenioView convenio(String estado, boolean vigente) {
		return new ConvenioView(140L, 20L, 31L, 88L, "OSDE-210", "OSDE 210", "POR_PRESTACION",
				ENERO, DICIEMBRE, vigente, "ARS", false, false, true, 20, null, null,
				estado, null, null, 0L);
	}

	private static ArancelView arancel() {
		return new ArancelView(901L, 140L, 412L, new BigDecimal("12000.00"),
				new BigDecimal("9600.00"), new BigDecimal("2400.00"), "ARS",
				ENERO, DICIEMBRE, true, "ACTIVO", null, null, 0L);
	}

	private static ArancelVigente vigente() {
		return new ArancelVigente(140L, "OSDE-210", "OSDE 210", "POR_PRESTACION", 31L, 88L, 412L,
				901L, new BigDecimal("12000.00"), new BigDecimal("9600.00"),
				new BigDecimal("2400.00"), "ARS", false, false, true, 20,
				ENERO, DICIEMBRE, ENERO, DICIEMBRE, LocalDate.of(2026, 3, 15));
	}
}
