package com.akine.offering.api;

import com.akine.offering.application.OperatingActor;
import com.akine.offering.application.ServicioAltaCommand;
import com.akine.offering.application.ServicioBusqueda;
import com.akine.offering.application.ServicioEdicionCommand;
import com.akine.offering.application.ServicioEstadoFiltro;
import com.akine.offering.application.ServicioService;
import com.akine.offering.application.ServicioView;
import com.akine.offering.domain.exception.ServicioCodigoTakenException;
import com.akine.offering.domain.exception.ServicioNotAccessibleException;
import com.akine.offering.domain.exception.ServicioYaInactivoException;
import com.akine.platform.spi.tenant.AuthenticatedPrincipal;
import com.akine.platform.spi.tenant.TenantContextHolder;
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

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * La capa REST del catalogo global de servicios (M27).
 *
 * <p>La autorizacion la decide {@code ServicioService} —rol de plataforma para escribir—, que
 * aca esta mockeado. Lo que esta capa si puede romper, y por eso se fija: que el flag de
 * plataforma del principal llegue al actor, que los errores del dominio salgan con su
 * {@code problemType} y no como 500, que la baja exija motivo y que el 204 no choque con el
 * {@code Accept: application/problem+json} del cliente generado.
 */
@WebMvcTest(ServicioController.class)
@Import({OfferingApiSliceSecurityConfig.class, OfferingApiActor.class, OfferingProblemHandler.class})
@DisplayName("ServicioController")
class ServicioControllerTest {

	private static final String RUTA = "/api/v1/servicios";

	@Autowired private MockMvc mockMvc;

	@MockitoBean private ServicioService servicioService;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@BeforeEach
	void setUp() {
		// El catalogo global no exige contexto de trabajo: se prueba sin ninguno.
		given(tenantContextHolder.current()).willReturn(Optional.empty());
	}

	@Test
	@DisplayName("listar pasa q y estado tal cual y publica el servicio")
	void listar() throws Exception {
		given(servicioService.buscar(any())).willReturn(List.of(vista()));

		mockMvc.perform(get(RUTA + "?q=kinesio&estado=TODOS").with(admin(true)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].codigo").value("KINESIOLOGIA_SESION"))
				.andExpect(jsonPath("$[0].estado").value("ACTIVO"));

		verify(servicioService).buscar(new ServicioBusqueda("kinesio", ServicioEstadoFiltro.TODOS));
	}

	@Test
	@DisplayName("crear: 201 con Location y el flag de plataforma llega al actor")
	void crear() throws Exception {
		given(servicioService.crear(any(), any())).willReturn(vista());

		mockMvc.perform(post(RUTA).with(admin(true)).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"codigo":"KINESIOLOGIA_SESION","nombre":"Sesion de kinesiologia",
								 "naturaleza":"TERAPEUTICO","modalidadDefault":"INDIVIDUAL"}
								"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/v1/servicios/12"))
				.andExpect(jsonPath("$.id").value(12));

		ArgumentCaptor<OperatingActor> actor = ArgumentCaptor.forClass(OperatingActor.class);
		ArgumentCaptor<ServicioAltaCommand> comando =
				ArgumentCaptor.forClass(ServicioAltaCommand.class);
		verify(servicioService).crear(actor.capture(), comando.capture());
		assertThat(actor.getValue().platformAdmin()).isTrue();
		assertThat(actor.getValue().accountId()).isEqualTo(99L);
		assertThat(comando.getValue().codigo()).isEqualTo("KINESIOLOGIA_SESION");
	}

	@Test
	@DisplayName("crear sin codigo es 400 y el servicio ni se entera")
	void crear_invalido() throws Exception {
		mockMvc.perform(post(RUTA).with(admin(true)).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"nombre":"Sesion","naturaleza":"TERAPEUTICO","modalidadDefault":"INDIVIDUAL"}
								"""))
				.andExpect(status().isBadRequest());

		verifyNoInteractions(servicioService);
	}

	@Test
	@DisplayName("codigo repetido: 409 servicio-codigo-taken")
	void codigo_repetido() throws Exception {
		given(servicioService.crear(any(), any()))
				.willThrow(new ServicioCodigoTakenException("KINESIOLOGIA_SESION"));

		mockMvc.perform(post(RUTA).with(admin(true)).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"codigo":"KINESIOLOGIA_SESION","nombre":"Sesion de kinesiologia",
								 "naturaleza":"TERAPEUTICO","modalidadDefault":"INDIVIDUAL"}
								"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(endsWith("servicio-codigo-taken")));
	}

	@Test
	@DisplayName("editar un servicio inexistente: 404 not-found; uno dado de baja: 409 servicio-inactivo")
	void editar_errores() throws Exception {
		given(servicioService.editar(any(), anyLong(), any()))
				.willThrow(new ServicioNotAccessibleException(12L))
				.willThrow(new ServicioYaInactivoException(12L, "editar"));
		String cuerpo = "{\"nombre\":\"Otro nombre\",\"expectedVersion\":3}";

		mockMvc.perform(put(RUTA + "/12").with(admin(true))
						.contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type").value(endsWith("not-found")));
		mockMvc.perform(put(RUTA + "/12").with(admin(true))
						.contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(endsWith("servicio-inactivo")));

		ArgumentCaptor<ServicioEdicionCommand> comando =
				ArgumentCaptor.forClass(ServicioEdicionCommand.class);
		verify(servicioService, org.mockito.Mockito.times(2))
				.editar(any(), eq(12L), comando.capture());
		// Edicion parcial: lo omitido viaja como null, no con un default inventado.
		assertThat(comando.getValue().descripcion()).isNull();
		assertThat(comando.getValue().expectedVersion()).isEqualTo(3L);
	}

	@Test
	@DisplayName("baja: 204 aun con Accept problem+json; dos veces: 409 servicio-already-inactive")
	void baja() throws Exception {
		given(servicioService.darDeBaja(any(), anyLong(), anyString()))
				.willReturn(vista())
				.willThrow(new ServicioYaInactivoException(12L, "dar de baja"));
		String cuerpo = "{\"reason\":\"Se discontinua\"}";

		mockMvc.perform(delete(RUTA + "/12").with(admin(true))
						.accept(MediaType.APPLICATION_PROBLEM_JSON)
						.contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andExpect(status().isNoContent());
		mockMvc.perform(delete(RUTA + "/12").with(admin(true))
						.contentType(MediaType.APPLICATION_JSON).content(cuerpo))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(endsWith("servicio-already-inactive")));

		verify(servicioService, org.mockito.Mockito.times(2))
				.darDeBaja(any(), eq(12L), eq("Se discontinua"));
	}

	@Test
	@DisplayName("baja sin motivo: 400 y no se da de baja nada")
	void baja_sin_motivo() throws Exception {
		mockMvc.perform(delete(RUTA + "/12").with(admin(true))
						.contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}"))
				.andExpect(status().isBadRequest());

		verify(servicioService, never()).darDeBaja(any(), anyLong(), any());
	}

	@Test
	@DisplayName("sin principal AKINE no se llega al servicio")
	void sin_sesion() throws Exception {
		mockMvc.perform(post(RUTA).contentType(MediaType.APPLICATION_JSON).content("""
						{"codigo":"X","nombre":"Y","naturaleza":"TERAPEUTICO","modalidadDefault":"INDIVIDUAL"}
						"""))
				.andExpect(status().is4xxClientError());

		verifyNoInteractions(servicioService);
	}

	// =================================================================================
	// Helpers
	// =================================================================================

	private static RequestPostProcessor admin(boolean plataforma) {
		return authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(99L, plataforma), "n/a", List.of()));
	}

	private static ServicioView vista() {
		return new ServicioView(12L, "KINESIOLOGIA_SESION", "Sesion de kinesiologia", null,
				"TERAPEUTICO", "INDIVIDUAL", false, true, "ACTIVO", null, null, 0L);
	}

	private record PrincipalDePrueba(long accountId, boolean platformAdmin)
			implements AuthenticatedPrincipal {

		@Override
		public Long organizationId() {
			return null;
		}

		@Override
		public Long consultorioId() {
			return null;
		}
	}
}
