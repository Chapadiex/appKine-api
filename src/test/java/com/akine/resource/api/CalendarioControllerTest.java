package com.akine.resource.api;

import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.resource.application.CalendarioService;
import com.akine.resource.application.CalendarioView;
import com.akine.resource.application.FeriadoView;
import com.akine.resource.domain.exception.ConsultorioNotAccessibleException;
import com.akine.resource.domain.exception.VentanaDemasiadoAmpliaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de la politica de calendario de una sede (RF-M05-004).
 *
 * <p>El test central de esta clase es
 * {@link #editar_solo_el_pais_deja_la_politica_de_feriados_intacta()}. Apagar
 * {@code cierraPorFeriado} abre de golpe todos los feriados del calendario para TODOS los
 * profesionales de la sede, hacia adelante y hacia atras; que ese flag sea {@link Boolean} y no
 * {@code boolean} es lo unico que impide que una edicion que solo queria cambiar el pais lo haga
 * en silencio. Nada mas que este test lo sostiene: un "limpiemos este nullable" compila, pasa
 * todo lo demas, y rompe la agenda del centro.
 */
@WebMvcTest(CalendarioController.class)
@Import({ResourceApiSliceSecurityConfig.class, ApiActor.class})
class CalendarioControllerTest {

	private static final String RUTA = "/api/v1/consultorios/20/calendario";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CalendarioService calendarioService;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	// =====================================================================================
	// PUT — la semantica de PATCH, que es donde vive el riesgo
	// =====================================================================================

	@Test
	@DisplayName("Editar SOLO el pais deja la politica de feriados intacta: cierraPorFeriado "
			+ "omitido llega como null al servicio, jamas como false")
	void editar_solo_el_pais_deja_la_politica_de_feriados_intacta() throws Exception {
		given(calendarioService.actualizar(any(), anyLong(), any(), any(), any()))
				.willReturn(politica("UY", true, 1L, true, List.of()));

		mockMvc.perform(put(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"pais\":\"UY\"}")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.pais").value("UY"))
				.andExpect(jsonPath("$.cierraPorFeriado").value(true));

		ArgumentCaptor<Boolean> cierra = ArgumentCaptor.forClass(Boolean.class);
		verify(calendarioService).actualizar(any(), eq(20L), eq("UY"), cierra.capture(), any());

		// Si el componente del DTO se declara boolean en vez de Boolean, Jackson pone false y
		// esta edicion —que solo queria cambiar el pais— apaga el cierre por feriados de toda
		// la sede sin que nadie lo pida. Este assert es lo unico que lo impide.
		assertThat(cierra.getValue())
				.as("cierraPorFeriado omitido tiene que llegar como null: null NO es false")
				.isNull();
	}

	@Test
	@DisplayName("Apagar el cierre por feriados si se puede pedir explicitamente: false llega "
			+ "como false y no se confunde con la ausencia")
	void apagar_el_cierre_por_feriados_llega_como_false() throws Exception {
		given(calendarioService.actualizar(any(), anyLong(), any(), any(), any()))
				.willReturn(politica("AR", false, 2L, true, List.of()));

		mockMvc.perform(put(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"cierraPorFeriado\":false}")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.cierraPorFeriado").value(false));

		verify(calendarioService).actualizar(any(), eq(20L), eq(null), eq(false), eq(null));
	}

	@Test
	@DisplayName("La respuesta del PUT trae feriados vacia, que significa no se pregunto y nunca "
			+ "no hay feriados: un PUT no lleva ventana")
	void la_respuesta_del_put_trae_feriados_vacia() throws Exception {
		given(calendarioService.actualizar(any(), anyLong(), any(), any(), any()))
				.willReturn(politica("AR", true, 1L, true, List.of()));

		mockMvc.perform(put(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"pais\":\"AR\"}")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.feriados").isEmpty())
				// La version viaja aunque la edicion no la exija: es lo que le permite a la
				// pantalla detectar que alguien mas la cambio.
				.andExpect(jsonPath("$.version").value(1));
	}

	@Test
	@DisplayName("Un pais que no es un codigo de dos letras devuelve 400, que es lo que el "
			+ "contrato promete: con Size a secas 12 pasaba la validacion")
	void un_pais_que_no_es_codigo_de_dos_letras_devuelve_400() throws Exception {
		mockMvc.perform(put(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"pais\":\"12\"}")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isBadRequest());

		verify(calendarioService, never()).actualizar(any(), anyLong(), any(), any(), any());
	}

	// =====================================================================================
	// GET
	// =====================================================================================

	@Test
	@DisplayName("La lectura de una sede sin fila propia devuelve existePersistida=false con los "
			+ "valores por defecto: dice que nadie edito nunca, no que no tenga politica")
	void la_lectura_de_una_sede_sin_fila_dice_que_no_esta_persistida() throws Exception {
		given(calendarioService.ver(any(), anyLong(), any(), any()))
				.willReturn(politica("AR", true, 0L, false,
						List.of(new FeriadoView(5L, "AR", LocalDate.of(2026, 7, 9),
								"Dia de la Independencia", "INAMOVIBLE"))));

		mockMvc.perform(get(RUTA)
						.param("desde", "2026-01-01")
						.param("hasta", "2027-01-01")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.existePersistida").value(false))
				.andExpect(jsonPath("$.pais").value("AR"))
				.andExpect(jsonPath("$.feriados[0].nombre").value("Dia de la Independencia"))
				// El feriado NO lleva organizationId: la tabla es global (ADR-0022).
				.andExpect(jsonPath("$.feriados[0].organizationId").doesNotExist());
	}

	@Test
	@DisplayName("Una ventana mas amplia que el tope devuelve 400 con maximoDias, tambien en el "
			+ "calendario: el tope vive en un solo lugar y las tres lecturas lo comparten")
	void una_ventana_demasiado_amplia_devuelve_400_con_el_maximo() throws Exception {
		willThrow(new VentanaDemasiadoAmpliaException(
				LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1), 366))
				.given(calendarioService).ver(any(), anyLong(), any(), any());

		mockMvc.perform(get(RUTA)
						.param("desde", "2020-01-01")
						.param("hasta", "2030-01-01")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type")
						.value("https://akine.app/problems/ventana-demasiado-amplia"))
				.andExpect(jsonPath("$.maximoDias").value(366));
	}

	@Test
	@DisplayName("Una sede de otro tenant devuelve 404 y no 403, tambien en el calendario")
	void una_sede_ajena_devuelve_404() throws Exception {
		willThrow(new ConsultorioNotAccessibleException(20L))
				.given(calendarioService).ver(any(), anyLong(), any(), any());

		mockMvc.perform(get(RUTA)
						.param("desde", "2026-01-01")
						.param("hasta", "2026-02-01")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"));
	}

	@Test
	@DisplayName("Sin sesion autenticada devuelve 403 y NO 401, tambien en el calendario")
	void sin_sesion_devuelve_403_y_no_401() throws Exception {
		mockMvc.perform(get(RUTA)
						.param("desde", "2026-01-01")
						.param("hasta", "2026-02-01"))
				.andExpect(status().isForbidden());

		verify(calendarioService, never()).ver(any(), anyLong(), any(), any());
	}

	// =====================================================================================
	// Fixtures sinteticas
	// =====================================================================================

	private static CalendarioView politica(
			String pais, boolean cierra, long version, boolean persistida,
			List<FeriadoView> feriados) {

		return new CalendarioView(20L, pais, cierra, version, persistida, feriados);
	}
}
