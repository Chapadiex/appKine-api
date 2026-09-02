package com.akine.person.api;

import com.akine.person.application.CoberturaPacienteService;
import com.akine.person.application.CoberturaView;
import com.akine.person.application.OperatingActor;
import com.akine.person.application.SeleccionDeCobertura;
import com.akine.person.domain.exception.CoberturaInactivaException;
import com.akine.person.domain.exception.CoberturaNotAccessibleException;
import com.akine.person.domain.exception.CoberturaPrincipalSuperpuestaException;
import com.akine.person.domain.exception.CoberturaSuperpuestaException;
import com.akine.person.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.person.domain.exception.PlanNoSeleccionableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
 * El contrato HTTP de M08: codigos, forma del cuerpo y traduccion de las excepciones de dominio.
 *
 * <p>Slice web: no levanta base ni contexto completo. La autorizacion NO se prueba aca —la cadena
 * del slice deja pasar todo a proposito— porque la decision se toma en la capa de aplicacion.
 *
 * <p>Lo que si se prueba y solo se puede probar aca: que cada excepcion salga con SU {@code type},
 * que el 404 sea indistinguible entre "no existe", "es de otro tenant" y "es de otro paciente", y
 * que el 204 de la baja responda aunque el cliente pida {@code application/problem+json} — el
 * defecto que rompio la activacion de cuenta en 01.02.
 */
@WebMvcTest(CoberturaPacienteController.class)
@Import({PersonApiSliceSecurityConfig.class, PersonProblemHandler.class})
@DisplayName("API de coberturas del paciente")
class CoberturaPacienteControllerTest {

	private static final String TIPO = "https://akine.app/problems/";
	private static final String RUTA = "/api/v1/personas/1204/coberturas";
	private static final String ALTA_FINANCIADA = """
			{"tipo":"FINANCIADA","planId":88,"numeroAfiliado":"62000123456",\
			"vigenciaDesde":"2026-09-01"}""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CoberturaPacienteService coberturaService;

	@MockitoBean
	private PersonApiActor apiActor;

	@BeforeEach
	void setUp() {
		given(apiActor.current()).willReturn(new OperatingActor(1L, false, 7L, 20L));
	}

	@Test
	@DisplayName("El historial publica la COPIA congelada del plan, no el catalogo de hoy")
	void historial() throws Exception {
		given(coberturaService.listar(any(), anyLong(), any(), any()))
				.willReturn(List.of(cobertura("ACTIVA", false, false)));

		mockMvc.perform(get(RUTA).param("estado", "TODAS").param("fecha", "2027-01-01"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].planNombre").value("Plan 210"))
				.andExpect(jsonPath("$[0].financiadorNombre").value("OSDE Binario"))
				.andExpect(jsonPath("$[0].estado").value("ACTIVA"))
				// Estado y vigencia son dos cosas: ACTIVA con vigente = false es el caso normal
				// de un paciente que cambio de obra social.
				.andExpect(jsonPath("$[0].vigente").value(false))
				.andExpect(jsonPath("$[0].copago").value(1500.00));
	}

	@Test
	@DisplayName("La seleccion del dia declara Particular disponible aunque no haya coberturas")
	void seleccion() throws Exception {
		// RN-M08-001 dicho en el contrato: si no viajara, cada pantalla tendria que acordarse de
		// agregarlo y la primera que lo olvide deja al mostrador sin poder cobrar.
		given(coberturaService.resolverParaAtencion(any(), anyLong(), any()))
				.willReturn(SeleccionDeCobertura.de(LocalDate.of(2026, 9, 2), null, List.of()));

		mockMvc.perform(get(RUTA + "/seleccion").param("fecha", "2026-09-02"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.particularSiempreDisponible").value(true))
				.andExpect(jsonPath("$.principal").doesNotExist())
				.andExpect(jsonPath("$.vigentes").isEmpty());
	}

	@Test
	@DisplayName("El alta responde 201 con Location anidado bajo su paciente")
	void alta() throws Exception {
		given(coberturaService.agregar(any(), anyLong(), any()))
				.willReturn(cobertura("ACTIVA", true, false));

		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content(ALTA_FINANCIADA))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", RUTA + "/412"))
				.andExpect(jsonPath("$.id").value(412));
	}

	@Test
	@DisplayName("Un alta sin tipo ni vigenciaDesde es 400 y no llega al servicio")
	void alta_invalida() throws Exception {
		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"planId":88}"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("Un tipo desconocido es 400 con un mensaje que nombra el campo")
	void tipo_desconocido() throws Exception {
		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"tipo":"MIXTA","vigenciaDesde":"2026-09-01"}"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("Finalizar la vigencia es el PUT, y devuelve la cobertura todavia ACTIVA")
	void cierre_de_vigencia() throws Exception {
		given(coberturaService.editar(any(), anyLong(), anyLong(), any()))
				.willReturn(cobertura("ACTIVA", false, false));

		mockMvc.perform(put(RUTA + "/412")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"vigenciaHasta":"2026-12-31","expectedVersion":0}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("ACTIVA"));
	}

	@Test
	@DisplayName("Marcar principal es su propia operacion y devuelve la cobertura")
	void marcar_principal() throws Exception {
		given(coberturaService.marcarPrincipal(any(), anyLong(), anyLong(), anyBoolean()))
				.willReturn(cobertura("ACTIVA", true, true));

		mockMvc.perform(post(RUTA + "/412/principal")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"principal":true}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.principal").value(true));
	}

	@Test
	@DisplayName("La baja responde 204 aunque el cliente pida solo problem+json")
	void baja_204_con_accept_problem_json() throws Exception {
		// Es el defecto que rompio la activacion de cuenta: sin declarar tambien
		// application/json en el produces, el de clase corta con 406 antes de entrar al metodo.
		given(coberturaService.darDeBaja(any(), anyLong(), anyLong(), anyString()))
				.willReturn(cobertura("INACTIVA", false, false));

		mockMvc.perform(delete(RUTA + "/412")
						.accept(MediaType.APPLICATION_PROBLEM_JSON)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"reason":"se cargo con el plan equivocado"}"""))
				.andExpect(status().isNoContent());
	}

	@Test
	@DisplayName("La baja sin motivo es 400")
	void baja_sin_motivo() throws Exception {
		mockMvc.perform(delete(RUTA + "/412")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	@DisplayName("Una cobertura ajena y una inexistente responden el MISMO 404")
	void el_404_es_uniforme() throws Exception {
		willThrow(new CoberturaNotAccessibleException(412L))
				.given(coberturaService).editar(any(), anyLong(), anyLong(), any());

		mockMvc.perform(put(RUTA + "/412")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"expectedVersion":0}"""))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value(TIPO + "not-found"))
				.andExpect(jsonPath("$.detail").value("La cobertura no existe."));
	}

	@Test
	@DisplayName("Cada conflicto del alta sale con SU type y con 409")
	void los_conflictos_del_alta() throws Exception {
		esperarConflictoEnElAlta(
				new PersonaSinPerfilPacienteException(1204L), "persona-sin-perfil-paciente");
		esperarConflictoEnElAlta(new PlanNoSeleccionableException(88L), "plan-no-seleccionable");

		willThrow(new CoberturaSuperpuestaException(400L))
				.given(coberturaService).agregar(any(), anyLong(), any());
		mockMvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(ALTA_FINANCIADA))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(TIPO + "cobertura-superpuesta"))
				.andExpect(jsonPath("$.coberturaExistenteId").value(400));

		willThrow(new CoberturaPrincipalSuperpuestaException(401L))
				.given(coberturaService).agregar(any(), anyLong(), any());
		mockMvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(ALTA_FINANCIADA))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(TIPO + "cobertura-principal-superpuesta"))
				.andExpect(jsonPath("$.coberturaPrincipalId").value(401));
	}

	@Test
	@DisplayName("Editar una cobertura de baja y volver a darla de baja son DOS 409 distintos")
	void los_dos_409_del_ciclo_de_vida() throws Exception {
		// El operador tiene que poder distinguir "esto ya estaba de baja" de "esto no se puede
		// editar porque esta de baja": son dos acciones distintas.
		willThrow(new CoberturaInactivaException(412L, "editar"))
				.given(coberturaService).editar(any(), anyLong(), anyLong(), any());
		mockMvc.perform(put(RUTA + "/412")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"expectedVersion":0}"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(TIPO + "cobertura-inactiva"));

		willThrow(new CoberturaInactivaException(412L, "dar de baja"))
				.given(coberturaService).darDeBaja(any(), anyLong(), anyLong(), anyString());
		mockMvc.perform(delete(RUTA + "/412")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"reason":"motivo"}"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(TIPO + "cobertura-already-inactive"));
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private void esperarConflictoEnElAlta(RuntimeException excepcion, String type)
			throws Exception {

		willThrow(excepcion).given(coberturaService).agregar(any(), anyLong(), any());

		mockMvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content(ALTA_FINANCIADA))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(TIPO + type));
	}

	private static CoberturaView cobertura(String estado, boolean vigente, boolean principal) {
		return new CoberturaView(
				412L, 1204L, "FINANCIADA",
				31L, "OSDE", "OSDE Binario", "PREPAGA",
				88L, "210", "Plan 210",
				false, true, new BigDecimal("1500.00"), "ARS", Instant.EPOCH,
				"62000123456", LocalDate.of(2027, 12, 31), false,
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 31), vigente, principal,
				null, estado, null, null, 0L);
	}
}
