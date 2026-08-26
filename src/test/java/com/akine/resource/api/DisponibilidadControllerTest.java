package com.akine.resource.api;

import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.resource.application.BloqueAltaCommand;
import com.akine.resource.application.BloqueView;
import com.akine.resource.application.DisponibilidadEfectivaService;
import com.akine.resource.application.DisponibilidadEfectivaView;
import com.akine.resource.application.DisponibilidadService;
import com.akine.resource.domain.IntervaloLocal;
import com.akine.resource.domain.exception.BloqueSolapadoException;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
 * Contrato HTTP de la disponibilidad profesional (RF-M05-003, RF-M05-005).
 *
 * <p>Lo que se verifica aca es exclusivamente la capa {@code api}: que el request se valide en
 * formato, que el actor se arme siempre desde el contexto revalidado, que cada excepcion de
 * dominio salga con el codigo y el {@code type} que el contrato promete, y que la frontera de
 * serializacion de la medianoche funcione en los dos sentidos. Las reglas de negocio son de
 * {@code application} y tienen sus propios tests.
 */
@WebMvcTest(DisponibilidadController.class)
@Import({ResourceApiSliceSecurityConfig.class, ApiActor.class})
class DisponibilidadControllerTest {

	private static final String RUTA =
			"/api/v1/consultorios/20/profesionales/30/disponibilidad";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private DisponibilidadService disponibilidadService;

	@MockitoBean
	private DisponibilidadEfectivaService disponibilidadEfectivaService;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	// =====================================================================================
	// POST — el alta responde 201 o 200, y esa diferencia es contrato
	// =====================================================================================

	@Test
	@DisplayName("El alta que CREA la fila devuelve 201 con la cabecera Location del bloque")
	void el_alta_devuelve_201_con_location() throws Exception {
		given(disponibilidadService.crear(any(), anyLong(), anyLong(), any()))
				.willReturn(bloque(50L, LocalTime.of(9, 0), LocalTime.of(13, 0), true));

		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"diaSemana":2,"horaDesde":"09:00","horaHasta":"13:00"}""")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", RUTA + "/50"))
				.andExpect(jsonPath("$.id").value(50))
				.andExpect(jsonPath("$.diaSemana").value(2))
				.andExpect(jsonPath("$.horaDesde").value("09:00"))
				.andExpect(jsonPath("$.estado").value("ACTIVO"))
				// El cero estructural de la sonda de impacto: publicado desde ya para que su
				// aparicion en F5 no sea un cambio de comportamiento sorpresivo.
				.andExpect(jsonPath("$.turnosAfectados").value(0));
	}

	@Test
	@DisplayName("El alta IDEMPOTENTE devuelve 200 sin Location, no 201: el reintento de red no "
			+ "creo nada y decirle al cliente que si lo haria mentir")
	void un_alta_idempotente_devuelve_200_y_no_201() throws Exception {
		given(disponibilidadService.crear(any(), anyLong(), anyLong(), any()))
				.willReturn(bloque(50L, LocalTime.of(9, 0), LocalTime.of(13, 0), false));

		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"diaSemana":2,"horaDesde":"09:00","horaHasta":"13:00"}""")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(header().doesNotExist("Location"))
				.andExpect(jsonPath("$.id").value(50));
	}

	@Test
	@DisplayName("La medianoche viaja como 24:00 en los dos sentidos: entra como LocalTime.MAX y "
			+ "sale como 24:00, para que el cliente pueda reenviar lo que leyo")
	void la_medianoche_hace_el_viaje_de_ida_y_vuelta_como_24_00() throws Exception {
		given(disponibilidadService.crear(any(), anyLong(), anyLong(), any()))
				.willReturn(bloque(51L, LocalTime.of(22, 0), IntervaloLocal.FIN_DE_DIA, true));

		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"diaSemana":2,"horaDesde":"22:00","horaHasta":"24:00"}""")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isCreated())
				// Ida y vuelta: lo que sale es exactamente lo que se puede volver a mandar.
				// Serializado en crudo, aca diria 23:59:59.999999999.
				.andExpect(jsonPath("$.horaHasta").value("24:00"));

		ArgumentCaptor<BloqueAltaCommand> comando =
				ArgumentCaptor.forClass(BloqueAltaCommand.class);
		verify(disponibilidadService).crear(any(), anyLong(), anyLong(), comando.capture());
		assertThat(comando.getValue().horaHasta())
				.as("24:00 tiene que entrar al dominio como el fin de dia, no como las 00:00 "
						+ "—que seria el principio— ni fallar el parseo")
				.isEqualTo(IntervaloLocal.FIN_DE_DIA);
	}

	@Test
	@DisplayName("Un solapamiento sale 409 como Problem Detail, con el bloque en conflicto para "
			+ "que la pantalla pueda decir cual mirar")
	void un_solapamiento_devuelve_409_como_problem_detail() throws Exception {
		willThrow(new BloqueSolapadoException(77L, 2, LocalTime.of(11, 0), LocalTime.of(15, 0)))
				.given(disponibilidadService).crear(any(), anyLong(), anyLong(), any());

		mockMvc.perform(post(RUTA)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"diaSemana":2,"horaDesde":"09:00","horaHasta":"13:00"}""")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type")
						.value("https://akine.app/problems/bloque-solapado"))
				.andExpect(jsonPath("$.bloqueEnConflictoId").value(77))
				.andExpect(jsonPath("$.horaDesde").value("11:00"));
	}

	// =====================================================================================
	// Los dos codigos que no se pueden confundir
	// =====================================================================================

	@Test
	@DisplayName("Una sede de otro tenant devuelve 404 y NO 403: un 403 confirmaria que existe y "
			+ "bastaria recorrer ids para reconstruir la agenda de cada centro")
	void una_sede_ajena_devuelve_404_y_no_403() throws Exception {
		willThrow(new ConsultorioNotAccessibleException(20L))
				.given(disponibilidadService).listar(any(), anyLong(), anyLong());

		mockMvc.perform(get(RUTA).with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/not-found"))
				// El detail es generico a proposito: dos textos distintos serian un oraculo de
				// que ids son reales.
				.andExpect(jsonPath("$.detail")
						.value("El recurso solicitado no existe o no esta disponible."));
	}

	@Test
	@DisplayName("Sin sesion ni contexto devuelve 403 y NO 401: el interceptor del frontend borra "
			+ "el token ante cualquier 401 y deja al usuario en un bucle de login")
	void sin_contexto_devuelve_403_y_no_401() throws Exception {
		mockMvc.perform(get(RUTA))
				.andExpect(status().isForbidden());

		verify(disponibilidadService, never()).listar(any(), anyLong(), anyLong());
	}

	// =====================================================================================
	// GET /efectiva — la ventana y el criterio de aceptacion de la etapa
	// =====================================================================================

	@Test
	@DisplayName("La efectiva sin desde ni hasta devuelve 400: la ventana no tiene default, "
			+ "porque un default silencioso devolveria un periodo que nadie pidio")
	void una_ventana_sin_desde_ni_hasta_devuelve_400() throws Exception {
		mockMvc.perform(get(RUTA + "/efectiva").with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isBadRequest());

		verify(disponibilidadEfectivaService, never())
				.efectiva(any(), anyLong(), anyLong(), any(), any());
	}

	@Test
	@DisplayName("Una ventana mas amplia que el tope devuelve 400 con maximoDias, para que la "
			+ "pantalla recorte sola en vez de mostrarle el error al usuario")
	void una_ventana_demasiado_amplia_devuelve_400_con_el_maximo() throws Exception {
		willThrow(new VentanaDemasiadoAmpliaException(
				LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1), 366))
				.given(disponibilidadEfectivaService)
				.efectiva(any(), anyLong(), anyLong(), any(), any());

		mockMvc.perform(get(RUTA + "/efectiva")
						.param("desde", "2020-01-01")
						.param("hasta", "2030-01-01")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type")
						.value("https://akine.app/problems/ventana-demasiado-amplia"))
				.andExpect(jsonPath("$.maximoDias").value(366));
	}

	@Test
	@DisplayName("La efectiva publica origen en cada franja y razonVacio en cada dia vacio: es el "
			+ "criterio de aceptacion de la etapa, no un extra")
	void la_efectiva_incluye_origen_en_cada_franja() throws Exception {
		given(disponibilidadEfectivaService.efectiva(any(), anyLong(), anyLong(), any(), any()))
				.willReturn(new DisponibilidadEfectivaView(30L, 20L, "America/Argentina/Cordoba",
						List.of(
								new DisponibilidadEfectivaView.DiaEfectivo(
										LocalDate.of(2026, 9, 1), false, null, null, null,
										List.of(new DisponibilidadEfectivaView.FranjaResuelta(
												Instant.parse("2026-09-01T12:00:00Z"),
												Instant.parse("2026-09-01T14:00:00Z"),
												"BLOQUE", "CIERRE", 501L))),
								new DisponibilidadEfectivaView.DiaEfectivo(
										LocalDate.of(2026, 9, 2), false, null, "VINCULO", null,
										List.of()))));

		mockMvc.perform(get(RUTA + "/efectiva")
						.param("desde", "2026-09-01")
						.param("hasta", "2026-09-03")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.timezone").value("America/Argentina/Cordoba"))
				// Sin estos tres campos la pantalla dibuja la franja pero no puede explicar de
				// donde salio ni por que quedo cortada.
				.andExpect(jsonPath("$.dias[0].franjas[0].origen").value("BLOQUE"))
				.andExpect(jsonPath("$.dias[0].franjas[0].recortadoPor").value("CIERRE"))
				.andExpect(jsonPath("$.dias[0].franjas[0].reglaId").value(501))
				// VINCULO es el cuarto valor de razonVacio y necesita texto propio: "no atiende
				// ese dia" y "ya no trabaja aca" no son lo mismo para quien mira la agenda.
				.andExpect(jsonPath("$.dias[1].razonVacio").value("VINCULO"))
				.andExpect(jsonPath("$.dias[1].franjas").isEmpty());
	}

	// =====================================================================================
	// DELETE — baja logica con motivo obligatorio en el cuerpo
	// =====================================================================================

	@Test
	@DisplayName("La baja sin motivo devuelve 400: una baja sin motivo no se puede revisar seis "
			+ "meses despues, que es exactamente cuando se la revisa")
	void la_baja_sin_motivo_devuelve_400() throws Exception {
		mockMvc.perform(delete(RUTA + "/50")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"  \"}")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isBadRequest());

		verify(disponibilidadService, never())
				.darDeBaja(any(), anyLong(), anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("La baja devuelve el bloque con turnosAfectados, no un 204 vacio: el impacto se "
			+ "informa y no bloquea (RN-M05-004)")
	void la_baja_devuelve_el_bloque_con_el_impacto() throws Exception {
		given(disponibilidadService.darDeBaja(any(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(bloqueDadoDeBaja());

		mockMvc.perform(delete(RUTA + "/50")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"Dejo de atender los martes\"}")
						.with(ResourceApiActors.miembro(7L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.estado").value("INACTIVO"))
				.andExpect(jsonPath("$.deactivationReason").value("Dejo de atender los martes"))
				.andExpect(jsonPath("$.turnosAfectados").value(0));

		verify(disponibilidadService)
				.darDeBaja(any(), anyLong(), anyLong(), anyLong(), any());
	}

	// =====================================================================================
	// Fixtures sinteticas
	// =====================================================================================

	private static BloqueView bloque(long id, LocalTime desde, LocalTime hasta, boolean nuevo) {
		return new BloqueView(id, 10L, 20L, 30L, 2, desde, hasta,
				LocalDate.of(2026, 3, 1), null, "ACTIVO", null, null, 0L, 0L, null, nuevo);
	}

	private static BloqueView bloqueDadoDeBaja() {
		return new BloqueView(50L, 10L, 20L, 30L, 2, LocalTime.of(9, 0), LocalTime.of(13, 0),
				LocalDate.of(2026, 3, 1), null, "INACTIVO",
				Instant.parse("2026-08-26T12:00:00Z"), "Dejo de atender los martes",
				1L, 0L, null, false);
	}
}
