package com.akine.contracting.api;

import com.akine.contracting.application.FinanciadorService;
import com.akine.contracting.application.FinanciadorView;
import com.akine.contracting.application.OperatingActor;
import com.akine.contracting.application.PlanCoberturaService;
import com.akine.contracting.application.PlanCoberturaView;
import com.akine.contracting.domain.exception.FinanciadorCodigoTakenException;
import com.akine.contracting.domain.exception.FinanciadorCuitTakenException;
import com.akine.contracting.domain.exception.FinanciadorInactivoException;
import com.akine.contracting.domain.exception.FinanciadorNombreTakenException;
import com.akine.contracting.domain.exception.FinanciadorNotAccessibleException;
import com.akine.contracting.domain.exception.FinanciadorYaInactivoException;
import com.akine.contracting.domain.exception.PlanCodigoTakenException;
import com.akine.contracting.domain.exception.PlanNombreTakenException;
import com.akine.contracting.domain.exception.PlanNotAccessibleException;
import com.akine.contracting.domain.exception.PlanYaInactivoException;
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
 * El contrato HTTP de M15: codigos, forma del cuerpo y traduccion de las excepciones de dominio.
 *
 * <p>Slice web: no levanta base de datos ni contexto completo. La autorizacion NO se prueba aca
 * —la cadena del slice deja pasar todo a proposito— porque la decision se toma en la capa de
 * aplicacion y la cubren {@code FinanciadorServiceTest} y {@code PlanCoberturaServiceTest}.
 *
 * <p>Lo que si se prueba y solo se puede probar aca: que cada excepcion de dominio salga con SU
 * {@code type}, que el 404 sea indistinguible entre "no existe" y "es de otro tenant", y que el
 * 204 de las bajas responda aunque el cliente pida {@code application/problem+json} — el defecto
 * que rompio la activacion de cuenta y que este slice existe, en parte, para no repetir.
 */
@WebMvcTest({FinanciadorController.class, PlanCoberturaController.class})
@Import({ContractingApiSliceSecurityConfig.class, ContractingProblemHandler.class})
@DisplayName("API de financiadores y planes")
class ContractingControllersTest {

	private static final String TIPO = "https://akine.app/problems/";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private FinanciadorService financiadorService;

	@MockitoBean
	private PlanCoberturaService planService;

	@MockitoBean
	private ContractingApiActor apiActor;

	@BeforeEach
	void setUp() {
		given(apiActor.current()).willReturn(new OperatingActor(1L, false, 7L, 20L));
	}

	// =================================================================================
	// Financiadores
	// =================================================================================

	@Nested
	@DisplayName("Financiadores")
	class Financiadores {

		@Test
		@DisplayName("El listado publica el codigo, el tipo y el estado derivado")
		void listado() throws Exception {
			given(financiadorService.buscar(any(), any())).willReturn(List.of(financiador("ACTIVO")));

			mockMvc.perform(get("/api/v1/financiadores").param("q", "osde"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[0].codigo").value("OSDE"))
					.andExpect(jsonPath("$[0].tipo").value("PREPAGA"))
					.andExpect(jsonPath("$[0].estado").value("ACTIVO"))
					.andExpect(jsonPath("$[0].cuit").value("30712345678"));
		}

		@Test
		@DisplayName("Un financiador INACTIVO se lee con 200, no con 404")
		void inactivo_se_lee_con_200() throws Exception {
			// RN-M15-003: los historicos que lo referencian tienen que seguir resolviendo.
			given(financiadorService.ver(any(), anyLong())).willReturn(financiador("INACTIVO"));

			mockMvc.perform(get("/api/v1/financiadores/31"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.estado").value("INACTIVO"));
		}

		@Test
		@DisplayName("El alta responde 201 con Location al recurso")
		void alta() throws Exception {
			given(financiadorService.crear(any(), any())).willReturn(financiador("ACTIVO"));

			mockMvc.perform(post("/api/v1/financiadores")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"codigo":"OSDE","nombre":"OSDE Binario","tipo":"PREPAGA"}"""))
					.andExpect(status().isCreated())
					.andExpect(header().string("Location", "/api/v1/financiadores/31"))
					.andExpect(jsonPath("$.id").value(31));
		}

		@Test
		@DisplayName("Un alta sin codigo ni tipo es 400 y no llega al servicio")
		void alta_invalida() throws Exception {
			mockMvc.perform(post("/api/v1/financiadores")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"nombre":"Sin codigo"}"""))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("La edicion responde 200 con el recurso actualizado")
		void edicion() throws Exception {
			given(financiadorService.editar(any(), anyLong(), any()))
					.willReturn(financiador("ACTIVO"));

			mockMvc.perform(put("/api/v1/financiadores/31")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"nombre":"OSDE Binario","expectedVersion":0}"""))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.nombre").value("OSDE Binario"));
		}

		@Test
		@DisplayName("La baja responde 204 aunque el cliente pida solo problem+json")
		void baja_204_con_accept_problem_json() throws Exception {
			// Es el defecto que rompio la activacion de cuenta: sin declarar tambien
			// application/json en el produces, el de clase corta con 406 antes de entrar al metodo.
			given(financiadorService.darDeBaja(any(), anyLong(), anyString()))
					.willReturn(financiador("INACTIVO"));

			mockMvc.perform(delete("/api/v1/financiadores/31")
							.accept(MediaType.APPLICATION_PROBLEM_JSON)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"reason":"dejamos de trabajar con ellos"}"""))
					.andExpect(status().isNoContent());
		}

		@Test
		@DisplayName("La baja sin motivo es 400")
		void baja_sin_motivo() throws Exception {
			mockMvc.perform(delete("/api/v1/financiadores/31")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{}"))
					.andExpect(status().isBadRequest());
		}
	}

	// =================================================================================
	// Planes
	// =================================================================================

	@Nested
	@DisplayName("Planes de cobertura")
	class Planes {

		@Test
		@DisplayName("El listado publica estado y vigente por separado")
		void listado() throws Exception {
			// Un plan ACTIVO con la vigencia vencida es el caso borde de la etapa. Colapsar los dos
			// campos dejaria a la pantalla sin poder explicar por que ese plan no se ofrece.
			given(planService.listar(any(), anyLong(), any(), any()))
					.willReturn(List.of(plan("ACTIVO", false)));

			mockMvc.perform(get("/api/v1/financiadores/31/planes")
							.param("estado", "TODOS")
							.param("fecha", "2026-06-01"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$[0].estado").value("ACTIVO"))
					.andExpect(jsonPath("$[0].vigente").value(false))
					.andExpect(jsonPath("$[0].financiadorId").value(31))
					.andExpect(jsonPath("$[0].copago").value(1500.00));
		}

		@Test
		@DisplayName("El alta responde 201 con Location anidado bajo su financiador")
		void alta() throws Exception {
			given(planService.crear(any(), anyLong(), any())).willReturn(plan("ACTIVO", true));

			mockMvc.perform(post("/api/v1/financiadores/31/planes")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"codigo":"210","nombre":"Plan 210","vigenciaDesde":"2026-01-01"}"""))
					.andExpect(status().isCreated())
					.andExpect(header().string("Location", "/api/v1/financiadores/31/planes/88"));
		}

		@Test
		@DisplayName("Un alta sin vigenciaDesde es 400")
		void alta_sin_vigencia() throws Exception {
			mockMvc.perform(post("/api/v1/financiadores/31/planes")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"codigo":"210","nombre":"Plan 210"}"""))
					.andExpect(status().isBadRequest());
		}

		@Test
		@DisplayName("Cerrar la vigencia es el PUT, y devuelve el plan todavia ACTIVO")
		void cierre_de_vigencia() throws Exception {
			given(planService.editar(any(), anyLong(), anyLong(), any()))
					.willReturn(plan("ACTIVO", false));

			mockMvc.perform(put("/api/v1/financiadores/31/planes/88")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"vigenciaHasta":"2026-06-30","expectedVersion":0}"""))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.estado").value("ACTIVO"));
		}

		@Test
		@DisplayName("La baja responde 204 aunque el cliente pida solo problem+json")
		void baja() throws Exception {
			given(planService.darDeBaja(any(), anyLong(), anyLong(), anyString()))
					.willReturn(plan("INACTIVO", false));

			mockMvc.perform(delete("/api/v1/financiadores/31/planes/88")
							.accept(MediaType.APPLICATION_PROBLEM_JSON)
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"reason":"ya no se ofrece"}"""))
					.andExpect(status().isNoContent());
		}
	}

	// =================================================================================
	// Problem Details
	// =================================================================================

	@Nested
	@DisplayName("Traduccion de errores")
	class Errores {

		@Test
		@DisplayName("Un id inexistente y uno de otro tenant responden el MISMO 404")
		void el_404_es_uniforme() throws Exception {
			// Distinguirlos confirmaria que ese id existe, y bastaria recorrer numeros para
			// averiguar con que obras sociales trabaja cada centro del SaaS.
			willThrow(new FinanciadorNotAccessibleException(31L))
					.given(financiadorService).ver(any(), anyLong());

			mockMvc.perform(get("/api/v1/financiadores/31"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.type").value(TIPO + "not-found"))
					.andExpect(jsonPath("$.detail").value("El financiador no existe."));
		}

		@Test
		@DisplayName("Un plan de otro financiador tambien es 404 bajo esa ruta")
		void plan_de_otro_financiador() throws Exception {
			willThrow(new PlanNotAccessibleException(88L))
					.given(planService).editar(any(), anyLong(), anyLong(), any());

			mockMvc.perform(put("/api/v1/financiadores/31/planes/88")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"expectedVersion":0}"""))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.type").value(TIPO + "not-found"));
		}

		@Test
		@DisplayName("Cada conflicto del financiador sale con SU type y con 409")
		void los_conflictos_del_financiador() throws Exception {
			esperarConflicto(new FinanciadorCodigoTakenException("OSDE"), "financiador-codigo-taken");
			esperarConflicto(new FinanciadorNombreTakenException(), "financiador-nombre-taken");
			esperarConflicto(new FinanciadorCuitTakenException(), "financiador-cuit-taken");
		}

		@Test
		@DisplayName("Editar un financiador inactivo y volver a darlo de baja son DOS 409 distintos")
		void los_dos_409_del_ciclo_de_vida() throws Exception {
			// El operador que los recibe tiene que poder distinguir "esto ya estaba de baja" de
			// "esto no se puede editar porque esta de baja": son dos acciones distintas.
			willThrow(new FinanciadorYaInactivoException(31L, "editar"))
					.given(financiadorService).editar(any(), anyLong(), any());
			mockMvc.perform(put("/api/v1/financiadores/31")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"expectedVersion":0}"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "financiador-inactivo"));

			willThrow(new FinanciadorYaInactivoException(31L, "dar de baja"))
					.given(financiadorService).darDeBaja(any(), anyLong(), anyString());
			mockMvc.perform(delete("/api/v1/financiadores/31")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"reason":"motivo"}"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "financiador-already-inactive"));
		}

		@Test
		@DisplayName("Crear un plan bajo un financiador dado de baja es 409 financiador-inactivo")
		void financiador_inactivo_en_el_alta_de_plan() throws Exception {
			// Es la contracara de que la baja no cascadee: lo que se impide es lo NUEVO.
			willThrow(new FinanciadorInactivoException(31L))
					.given(planService).crear(any(), anyLong(), any());

			mockMvc.perform(post("/api/v1/financiadores/31/planes")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"codigo":"210","nombre":"Plan 210","vigenciaDesde":"2026-01-01"}"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "financiador-inactivo"));
		}

		@Test
		@DisplayName("Cada conflicto del plan sale con SU type y con 409")
		void los_conflictos_del_plan() throws Exception {
			esperarConflictoDePlan(new PlanCodigoTakenException("210"), "plan-cobertura-codigo-taken");
			esperarConflictoDePlan(new PlanNombreTakenException(), "plan-cobertura-nombre-taken");

			willThrow(new PlanYaInactivoException(88L, "editar"))
					.given(planService).editar(any(), anyLong(), anyLong(), any());
			mockMvc.perform(put("/api/v1/financiadores/31/planes/88")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"expectedVersion":0}"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "plan-cobertura-inactivo"));

			willThrow(new PlanYaInactivoException(88L, "dar de baja"))
					.given(planService).darDeBaja(any(), anyLong(), anyLong(), anyString());
			mockMvc.perform(delete("/api/v1/financiadores/31/planes/88")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"reason":"motivo"}"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + "plan-cobertura-already-inactive"));
		}

		private void esperarConflicto(RuntimeException excepcion, String type) throws Exception {
			willThrow(excepcion).given(financiadorService).crear(any(), any());

			mockMvc.perform(post("/api/v1/financiadores")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"codigo":"OSDE","nombre":"OSDE Binario","tipo":"PREPAGA"}"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + type));
		}

		private void esperarConflictoDePlan(RuntimeException excepcion, String type)
				throws Exception {

			willThrow(excepcion).given(planService).crear(any(), anyLong(), any());

			mockMvc.perform(post("/api/v1/financiadores/31/planes")
							.contentType(MediaType.APPLICATION_JSON)
							.content("""
									{"codigo":"210","nombre":"Plan 210","vigenciaDesde":"2026-01-01"}"""))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.type").value(TIPO + type));
		}
	}

	// =================================================================================
	// Apoyo
	// =================================================================================

	private static FinanciadorView financiador(String estado) {
		return new FinanciadorView(
				31L, "OSDE", "OSDE Binario", "PREPAGA", "30712345678",
				"admin@osde.test", "011-4000-0000", null, estado, null, null, 0L);
	}

	private static PlanCoberturaView plan(String estado, boolean vigente) {
		return new PlanCoberturaView(
				88L, 31L, "210", "Plan 210", null,
				LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), vigente,
				false, true, new BigDecimal("1500.00"), "ARS", estado, null, null, 0L);
	}
}
