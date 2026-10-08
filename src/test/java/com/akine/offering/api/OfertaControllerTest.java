package com.akine.offering.api;

import com.akine.offering.application.OfertaAltaCommand;
import com.akine.offering.application.OfertaEdicionCommand;
import com.akine.offering.application.OfertaEstadoFiltro;
import com.akine.offering.application.OfertaService;
import com.akine.offering.application.OfertaView;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.RequestTenantContext;
import com.akine.platform.spi.tenant.TenantContextHolder;
import com.akine.platform.spi.tenant.TenantOperationalStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La capa REST de las ofertas de una sede (M27).
 *
 * <h2>Que decide esta capa, y que no</h2>
 *
 * <p>La capa api <b>no decide reglas de negocio</b>: traduce. Lo que se verifica aca es lo que solo
 * ella puede romper — que el filtro por defecto sea el correcto, que los booleanos omitidos lleguen
 * con el valor que el dominio espera y no como {@code null}, y que la baja exija su motivo en el
 * cuerpo.
 *
 * <p><b>El filtro por defecto importa.</b> Listar sin parametro devuelve las ACTIVAS: si devolviera
 * todas, la pantalla de una sede con tres anios de historia mostraria ofertas dadas de baja
 * mezcladas con las vigentes y alguien terminaria agendando sobre una que ya no se presta.
 */
@WebMvcTest(OfertaController.class)
@Import({OfferingApiSliceSecurityConfig.class, OfferingApiActor.class})
@DisplayName("OfertaController")
class OfertaControllerTest {

	private static final long CONSULTORIO_ID = 3L;
	private static final long ORG_ID = 1L;
	private static final long CUENTA = 99L;
	private static final String RUTA = "/api/v1/consultorios/3/ofertas";

	@Autowired private MockMvc mockMvc;

	@MockitoBean private OfertaService ofertaService;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@BeforeEach
	void setUp() {
		given(tenantContextHolder.current()).willReturn(Optional.of(new RequestTenantContext(
				CUENTA, ORG_ID, CONSULTORIO_ID, "ORG_ADMIN", TenantOperationalStatus.ACTIVA)));
	}

	@Test
	@DisplayName("Listar sin filtro trae las ACTIVAS: una baja no se mezcla con las vigentes")
	void el_filtro_por_defecto_es_activo() {
		given(ofertaService.listar(any(), anyLong(), anyLong(), any())).willReturn(List.of(vista()));

		perform(get(RUTA), status().isOk());

		verify(ofertaService).listar(any(), eq(ORG_ID), eq(CONSULTORIO_ID),
				eq(OfertaEstadoFiltro.ACTIVO));
	}

	@Test
	@DisplayName("El filtro explicito llega tal cual al servicio")
	void el_filtro_explicito_viaja() {
		given(ofertaService.listar(any(), anyLong(), anyLong(), any())).willReturn(List.of());

		perform(get(RUTA + "?estado=TODOS"), status().isOk());

		verify(ofertaService).listar(any(), anyLong(), anyLong(), eq(OfertaEstadoFiltro.TODOS));
	}

	@Test
	@DisplayName("Con servicioId la consulta cambia de metodo: no es el mismo listado filtrado")
	void listar_por_servicio() {
		// El catalogo global y las ofertas de la sede son dos poblaciones distintas, y la consulta
		// por servicio existe para la pantalla que arma la oferta desde el catalogo.
		given(ofertaService.listarPorServicio(any(), anyLong(), anyLong(), anyLong()))
				.willReturn(List.of(vista()));

		perform(get(RUTA + "?servicioId=12"), status().isOk());

		verify(ofertaService).listarPorServicio(any(), eq(ORG_ID), eq(CONSULTORIO_ID), eq(12L));
	}

	@Test
	@DisplayName("La respuesta publica el estado y la vigencia de hoy, no solo las fechas")
	void la_respuesta_dice_el_estado() {
		// La pantalla no tiene por que recalcular si una oferta con vigencia hasta ayer esta
		// vigente: el backend ya lo sabe y publicarlo evita que cada cliente lo resuelva distinto.
		given(ofertaService.listar(any(), anyLong(), anyLong(), any())).willReturn(List.of(vista()));

		perform(get(RUTA), status().isOk(),
				jsonPath("$[0].estado").value("ACTIVO"),
				jsonPath("$[0].vigenteHoy").value(true),
				jsonPath("$[0].nombreComercial").value("Kinesiologia - 45 minutos"));
	}

	@Test
	@DisplayName("Crear devuelve 201 y los booleanos omitidos NO llegan como null al dominio")
	void crear_resuelve_los_booleanos_omitidos() {
		// Si llegaran como null, el dominio tendria que decidir el default y el mismo pedido
		// significaria cosas distintas segun quien lo mande.
		given(ofertaService.crear(any(), anyLong(), anyLong(), any())).willReturn(vista());

		perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content("""
				{"servicioId":12,"nombreComercial":"Kinesiologia - 45 minutos",
				 "modalidad":"INDIVIDUAL","duracionMinutos":45,"capacidad":1,
				 "precioBase":8500.00,"moneda":"ARS","vigenciaDesde":"2026-01-01"}
				"""), status().isCreated());

		ArgumentCaptor<OfertaAltaCommand> comando =
				ArgumentCaptor.forClass(OfertaAltaCommand.class);
		verify(ofertaService).crear(any(), eq(ORG_ID), eq(CONSULTORIO_ID), comando.capture());
		assertThat(comando.getValue()).isNotNull();
	}

	@Test
	@DisplayName("DP-21: editar con una version vieja es 409 concurrent-modification, no conflict")
	void editar_con_version_vieja() {
		given(ofertaService.editar(any(), anyLong(), anyLong(), anyLong(), any()))
				.willThrow(new org.springframework.dao.OptimisticLockingFailureException(
						"La oferta fue modificada por otra operacion"));

		perform(put(RUTA + "/77").contentType(MediaType.APPLICATION_JSON).content("""
				{"nombreComercial":"Kinesiologia - 45 minutos","duracionMinutos":45,
				 "capacidad":1,"precioBase":8500.00,"moneda":"ARS",
				 "vigenciaDesde":"2026-01-01","expectedVersion":0}
				"""), status().isConflict(),
				org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.type")
						.value("https://akine.app/problems/concurrent-modification"));
	}

	@Test
	@DisplayName("Editar manda el id de la oferta y el cuerpo al servicio")
	void editar() {
		given(ofertaService.editar(any(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(vista());

		perform(put(RUTA + "/77").contentType(MediaType.APPLICATION_JSON).content("""
				{"nombreComercial":"Kinesiologia - 45 minutos","duracionMinutos":45,
				 "capacidad":1,"precioBase":8500.00,"moneda":"ARS",
				 "vigenciaDesde":"2026-01-01","expectedVersion":0}
				"""), status().isOk());

		ArgumentCaptor<OfertaEdicionCommand> comando =
				ArgumentCaptor.forClass(OfertaEdicionCommand.class);
		verify(ofertaService).editar(
				any(), eq(ORG_ID), eq(CONSULTORIO_ID), eq(77L), comando.capture());
		assertThat(comando.getValue()).isNotNull();
	}

	@Test
	@DisplayName("La baja exige motivo en el cuerpo y responde 204 sin contenido")
	void dar_de_baja() {
		// El motivo no es opcional: una oferta que desaparece de la pantalla sin explicacion deja
		// al centro sin saber si fue un error o una decision.
		given(ofertaService.darDeBaja(any(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(vista());

		// El campo se llama `reason`, en ingles, mientras el resto del cuerpo esta en castellano.
		// Queda fijado por test: cambiarlo seria un cambio incompatible de contrato.
		perform(delete(RUTA + "/77").contentType(MediaType.APPLICATION_JSON)
				.content("{\"reason\":\"Se dejo de prestar\"}"), status().isNoContent());

		verify(ofertaService).darDeBaja(
				any(), eq(ORG_ID), eq(CONSULTORIO_ID), eq(77L), eq("Se dejo de prestar"));
	}

	// =================================================================================
	// Helpers
	// =================================================================================

	private void perform(
			org.springframework.test.web.servlet.RequestBuilder peticion,
			org.springframework.test.web.servlet.ResultMatcher... matchers) {

		try {
			var resultado = mockMvc.perform(
					((org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder)
							peticion).with(miembro()));
			for (var matcher : matchers) {
				resultado.andExpect(matcher);
			}
		} catch (Exception fallo) {
			throw new AssertionError("La peticion fallo: " + fallo.getMessage(), fallo);
		}
	}

	private static RequestPostProcessor miembro() {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(CUENTA), "n/a", List.of()));
	}

	private static OfertaView vista() {
		return new OfertaView(77L, ORG_ID, CONSULTORIO_ID, 12L, "Kinesiologia - 45 minutos",
				"Sesion individual", "INDIVIDUAL", 45, 1, new BigDecimal("8500.00"), "ARS",
				"POR_SESION", true, false, false, true, true, true,
				LocalDate.of(2026, 1, 1), null, "ACTIVO", true, null, null, 0L);
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
			return CONSULTORIO_ID;
		}
	}
}
