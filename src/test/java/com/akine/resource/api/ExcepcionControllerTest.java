package com.akine.resource.api;

import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.resource.application.ExcepcionService;
import com.akine.resource.application.ExcepcionView;
import com.akine.resource.domain.IntervaloLocal;
import com.akine.resource.domain.exception.ConsultorioNotOperableException;
import com.akine.resource.domain.exception.ExcepcionInactivaException;
import com.akine.resource.domain.exception.ExcepcionNotAccessibleException;
import com.akine.resource.domain.exception.ProfesionalNoVinculadoException;
import com.akine.resource.domain.exception.ProfesionalNotAccessibleException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de los cierres y aperturas puntuales (RF-M05-004).
 *
 * <p>Este controller construye su {@code Location} por su cuenta —su ruta no cuelga del
 * profesional— y su listado tiene dos comportamientos distintos segun venga o no
 * {@code membershipId}. Las dos cosas son propiedades del contrato que ningun test de
 * {@code application} puede fijar, porque viven en la traduccion HTTP.
 */
@WebMvcTest(ExcepcionController.class)
@Import({ResourceApiSliceSecurityConfig.class, ApiActor.class})
class ExcepcionControllerTest {

	private static final String RUTA = "/api/v1/consultorios/20/excepciones";

	private static final String CUERPO_ALTA = """
			{"tipo":"CIERRE","motivo":"LICENCIA",
			 "fechaDesde":"2026-09-07","fechaHasta":"2026-09-08"}""";

	private static final String CUERPO_ALTA_CON_PROFESIONAL = """
			{"membershipId":30,"tipo":"CIERRE","motivo":"LICENCIA",
			 "fechaDesde":"2026-09-07","fechaHasta":"2026-09-08"}""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ExcepcionService excepcionService;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	// =====================================================================================
	// POST — 201 y 200, con su propia construccion de Location
	// =====================================================================================

	@Test
	@DisplayName("El alta que CREA devuelve 201 con el Location de la ruta de excepciones, que "
			+ "NO es la de disponibilidad: cada controller arma la suya")
	void el_alta_devuelve_201_con_su_propio_location() throws Exception {
		given(excepcionService.crear(any(), anyLong(), any())).willReturn(excepcion(90L, true));

		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content(CUERPO_ALTA)
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", RUTA + "/90"))
				.andExpect(jsonPath("$.id").value(90))
				.andExpect(jsonPath("$.tipo").value("CIERRE"))
				.andExpect(jsonPath("$.estado").value("ACTIVO"));
	}

	@Test
	@DisplayName("El alta IDEMPOTENTE devuelve 200 sin Location: el reintento de red no creo "
			+ "ninguna fila y decir 201 seria mentirle al cliente")
	void un_alta_idempotente_devuelve_200_y_no_201() throws Exception {
		given(excepcionService.crear(any(), anyLong(), any())).willReturn(excepcion(90L, false));

		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content(CUERPO_ALTA)
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Location"))
				.andExpect(jsonPath("$.id").value(90));
	}

	@Test
	@DisplayName("La medianoche de una excepcion tambien viaja como 24:00: la frontera esta "
			+ "protegida en las DOS respuestas que llevan horas, no solo en la de bloques")
	void la_medianoche_de_una_excepcion_sale_como_24_00() throws Exception {
		given(excepcionService.crear(any(), anyLong(), any()))
				.willReturn(excepcionConHoras(LocalTime.of(14, 0), IntervaloLocal.FIN_DE_DIA));

		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"tipo":"CIERRE","motivo":"LICENCIA",
								 "fechaDesde":"2026-09-07","fechaHasta":"2026-09-08",
								 "horaDesde":"14:00","horaHasta":"24:00"}""")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.horaDesde").value("14:00"))
				// Sin el serializador aca dice 23:59:59.999999999, que el cliente no puede
				// reenviar. Este campo estaba desprotegido por los tests hasta la ronda 1.
				.andExpect(jsonPath("$.horaHasta").value("24:00"));
	}

	@Test
	@DisplayName("El alta sobre una sede dada de baja devuelve 409 consultorio-inactive, que es "
			+ "uno de los DOS unicos 409 de esta operacion")
	void el_alta_sobre_una_sede_inactiva_devuelve_409() throws Exception {
		willThrow(new ConsultorioNotOperableException(20L))
				.given(excepcionService).crear(any(), anyLong(), any());

		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content(CUERPO_ALTA)
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type")
						.value("https://akine.app/problems/consultorio-inactive"));
	}

	@Test
	@DisplayName("Un profesional sin vinculo vigente en la sede devuelve 409, y NO 403 ni 404: "
			+ "el administrador puede hacerlo, lo que no atiende ahi es el profesional")
	void un_profesional_no_vinculado_devuelve_409_con_los_dos_ids() throws Exception {
		willThrow(new ProfesionalNoVinculadoException(30L, 20L))
				.given(excepcionService).crear(any(), anyLong(), any());

		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content(CUERPO_ALTA_CON_PROFESIONAL)
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type")
						.value("https://akine.app/problems/profesional-no-vinculado"))
				.andExpect(jsonPath("$.membershipId").value(30))
				.andExpect(jsonPath("$.consultorioId").value(20));
	}

	@Test
	@DisplayName("Un profesional de otro tenant devuelve 404 y no 409: no existe para vos y no "
			+ "atiende en esta sede son dos hechos distintos")
	void un_profesional_de_otro_tenant_devuelve_404() throws Exception {
		willThrow(new ProfesionalNotAccessibleException(30L))
				.given(excepcionService).crear(any(), anyLong(), any());

		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content(CUERPO_ALTA_CON_PROFESIONAL)
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"));
	}

	// =====================================================================================
	// GET — las dos poblaciones, que son una propiedad de alcance y no una comodidad
	// =====================================================================================

	@Test
	@DisplayName("SIN membershipId el listado pide solo las de SEDE: el null viaja tal cual al "
			+ "servicio y no se convierte en un id centinela")
	void el_listado_sin_membership_pide_solo_las_de_sede() throws Exception {
		given(excepcionService.listar(any(), anyLong(), any(), any(), isNull()))
				.willReturn(List.of(excepcion(90L, false)));

		mockMvc.perform(get(RUTA)
						.param("desde", "2026-09-01")
						.param("hasta", "2026-10-01")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(90));

		// Si alguien le pone un default al parametro, este verify falla: la vista de sede
		// empezaria a mostrar en silencio las excepciones de un profesional cualquiera.
		verify(excepcionService).listar(any(), eq(20L),
				eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 10, 1)), isNull());
	}

	@Test
	@DisplayName("CON membershipId el listado lo reenvia al servicio, que agrega las de sede a "
			+ "las del profesional")
	void el_listado_con_membership_lo_reenvia() throws Exception {
		given(excepcionService.listar(any(), anyLong(), any(), any(), any()))
				.willReturn(List.of());

		mockMvc.perform(get(RUTA)
						.param("desde", "2026-09-01")
						.param("hasta", "2026-10-01")
						.param("membershipId", "30")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isOk());

		verify(excepcionService).listar(any(), eq(20L),
				eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 10, 1)), eq(30L));
	}

	@Test
	@DisplayName("El listado sin ventana devuelve 400: la ventana no tiene default silencioso")
	void el_listado_sin_ventana_devuelve_400() throws Exception {
		mockMvc.perform(get(RUTA).with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isBadRequest());

		verify(excepcionService, never()).listar(any(), anyLong(), any(), any(), any());
	}

	// =====================================================================================
	// DELETE
	// =====================================================================================

	@Test
	@DisplayName("La baja repetida devuelve 409 excepcion-already-inactive: ya estaba dada de "
			+ "baja es informacion distinta de no existe")
	void la_baja_repetida_devuelve_409() throws Exception {
		willThrow(new ExcepcionInactivaException(90L))
				.given(excepcionService).darDeBaja(any(), anyLong(), anyLong(), any());

		mockMvc.perform(delete(RUTA + "/90")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Ya no aplica\"}")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type")
						.value("https://akine.app/problems/excepcion-already-inactive"));
	}

	@Test
	@DisplayName("Una excepcion de otra sede devuelve 404 con el detail generico")
	void una_excepcion_ajena_devuelve_404() throws Exception {
		willThrow(new ExcepcionNotAccessibleException(90L))
				.given(excepcionService).darDeBaja(any(), anyLong(), anyLong(), any());

		mockMvc.perform(delete(RUTA + "/90")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Ya no aplica\"}")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"));
	}

	@Test
	@DisplayName("Un DELETE sin cuerpo ni Content-Type devuelve 415 y no 400: la operacion exige "
			+ "cuerpo, asi que el request no llega siquiera a validarse")
	void una_baja_sin_content_type_devuelve_415() throws Exception {
		mockMvc.perform(delete(RUTA + "/90").with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isUnsupportedMediaType());

		verify(excepcionService, never()).darDeBaja(any(), anyLong(), anyLong(), any());
	}

	// =====================================================================================
	// Fixtures sinteticas
	// =====================================================================================

	private static ExcepcionView excepcion(long id, boolean nuevo) {
		return new ExcepcionView(id, 10L, 20L, null, "CIERRE", "LICENCIA",
				LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 8), null, null, null, null,
				"ACTIVO", null, null, 0L, nuevo);
	}

	private static ExcepcionView excepcionConHoras(LocalTime desde, LocalTime hasta) {
		return new ExcepcionView(90L, 10L, 20L, null, "CIERRE", "LICENCIA",
				LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 8), desde, hasta, null, null,
				"ACTIVO", null, null, 0L, true);
	}
}
