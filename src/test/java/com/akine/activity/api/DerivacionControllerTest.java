package com.akine.activity.api;

import com.akine.activity.application.DerivacionService;
import com.akine.activity.application.DerivacionView;
import com.akine.activity.application.DerivarParticipanteCommand;
import com.akine.activity.application.ElegibilidadClinicaView;
import com.akine.activity.application.EstadoDeDerivacionView;
import com.akine.activity.application.OperatingActor;
import com.akine.activity.domain.exception.DerivacionNoAccesibleException;
import com.akine.activity.domain.exception.OfertaNoClinicaException;
import com.akine.activity.domain.exception.ParticipacionSinAsistenciaException;
import com.akine.activity.domain.exception.PersonaSinPerfilPacienteException;
import com.akine.platform.spi.problem.ProblemType;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * La capa REST de la derivacion de participantes (AKINE-08.04).
 *
 * <p>Lo que solo esta capa puede romper: 201 con {@code Location} al crear y 200 al repetir, que
 * la cabecera de justificacion llegue al servicio, que la bandera ausente valga {@code false}, la
 * validacion de los cuerpos y que cada desenlace salga con su {@code problemType}. Las reglas las
 * cubre {@code DerivacionParticipanteIT} contra MySQL.
 */
@WebMvcTest(DerivacionController.class)
@Import({ActivityApiSliceSecurityConfig.class, ActivityApiActor.class})
@DisplayName("Controller de derivacion de participantes")
class DerivacionControllerTest {

	private static final long ORG_ID = 1L;
	private static final long SEDE_ID = 10L;
	private static final long CLASE_ID = 77L;
	private static final long INSCRIPCION_ID = 301L;
	private static final long CUENTA = 99L;
	private static final String BASE = "/api/v1/consultorios/10/clases/77/inscripciones/301";

	@Autowired private MockMvc mockMvc;

	@MockitoBean private DerivacionService derivacionService;
	@MockitoBean private TenantContextHolder tenantContextHolder;

	@BeforeEach
	void setUp() {
		given(tenantContextHolder.current()).willReturn(Optional.of(new RequestTenantContext(
				CUENTA, ORG_ID, SEDE_ID, "PROFESIONAL", TenantOperationalStatus.ACTIVA)));
	}

	@Test
	@DisplayName("Derivar: 201 con Location; repetir al mismo Caso, 200 con yaExistia")
	void derivar_201_y_200() throws Exception {
		given(derivacionService.derivar(any(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(derivacion(false))
				.willReturn(derivacion(true));
		String cuerpo = "{\"casoClinicoId\":88,\"motivo\":\"Indicacion\"}";

		autenticado(post(BASE + "/derivaciones").contentType(MediaType.APPLICATION_JSON)
				.header(DerivacionController.JUSTIFICACION, "Control post clase")
				.content(cuerpo))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", BASE + "/derivaciones/501"))
				.andExpect(jsonPath("$.estado").value("VIGENTE"))
				.andExpect(jsonPath("$.casoClinicoId").value(88))
				.andExpect(jsonPath("$.yaExistia").value(false));
		autenticado(post(BASE + "/derivaciones").contentType(MediaType.APPLICATION_JSON)
				.content(cuerpo))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.yaExistia").value(true));

		ArgumentCaptor<DerivarParticipanteCommand> comando =
				ArgumentCaptor.forClass(DerivarParticipanteCommand.class);
		ArgumentCaptor<OperatingActor> actor = ArgumentCaptor.forClass(OperatingActor.class);
		verify(derivacionService, org.mockito.Mockito.times(2)).derivar(
				actor.capture(), eq(SEDE_ID), eq(CLASE_ID), eq(INSCRIPCION_ID), comando.capture());
		assertThat(actor.getValue()).isEqualTo(new OperatingActor(CUENTA, false, ORG_ID, SEDE_ID));
		assertThat(comando.getAllValues().get(0)).isEqualTo(new DerivarParticipanteCommand(
				88L, null, null, "Indicacion", false, "Control post clase"));
		assertThat(comando.getAllValues().get(1).justificacion()).isNull();
	}

	@Test
	@DisplayName("activarPerfilPaciente: true llega al servicio; ausente vale false")
	void la_bandera_explicita_llega() throws Exception {
		given(derivacionService.derivar(any(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(derivacion(false));

		autenticado(post(BASE + "/derivaciones").contentType(MediaType.APPLICATION_JSON)
				.content("{\"casoClinicoId\":88,\"planTratamientoId\":12,\"autorizacionId\":7,"
						+ "\"activarPerfilPaciente\":true}"))
				.andExpect(status().isCreated());

		ArgumentCaptor<DerivarParticipanteCommand> comando =
				ArgumentCaptor.forClass(DerivarParticipanteCommand.class);
		verify(derivacionService).derivar(any(), anyLong(), anyLong(), anyLong(), comando.capture());
		assertThat(comando.getValue()).isEqualTo(
				new DerivarParticipanteCommand(88L, 12L, 7L, null, true, null));
	}

	@Test
	@DisplayName("Sin casoClinicoId valido es 400 y el servicio no se llama")
	void caso_invalido_es_400() throws Exception {
		autenticado(post(BASE + "/derivaciones").contentType(MediaType.APPLICATION_JSON)
				.content("{\"casoClinicoId\":0}"))
				.andExpect(status().isBadRequest());

		verify(derivacionService, never()).derivar(any(), anyLong(), anyLong(), anyLong(), any());
	}

	@Test
	@DisplayName("Los tres bloqueos grupales salen con su problemType")
	void bloqueos_grupales() throws Exception {
		given(derivacionService.derivar(any(), anyLong(), anyLong(), anyLong(), any()))
				.willThrow(new OfertaNoClinicaException(45L))
				.willThrow(new ParticipacionSinAsistenciaException(INSCRIPCION_ID, "AUSENTE"))
				.willThrow(new PersonaSinPerfilPacienteException(512L));
		String cuerpo = "{\"casoClinicoId\":88}";

		autenticado(post(BASE + "/derivaciones").contentType(MediaType.APPLICATION_JSON)
				.content(cuerpo))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(ProblemType.OFERTA_NO_CLINICA.uri().toString()))
				.andExpect(jsonPath("$.ofertaId").value(45));
		autenticado(post(BASE + "/derivaciones").contentType(MediaType.APPLICATION_JSON)
				.content(cuerpo))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type")
						.value(ProblemType.PARTICIPACION_SIN_ASISTENCIA.uri().toString()))
				.andExpect(jsonPath("$.resultado").value("AUSENTE"));
		autenticado(post(BASE + "/derivaciones").contentType(MediaType.APPLICATION_JSON)
				.content(cuerpo))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type")
						.value(ProblemType.PERSONA_SIN_PERFIL_PACIENTE.uri().toString()))
				.andExpect(jsonPath("$.personaId").value(512));
	}

	@Test
	@DisplayName("Listar pasa la justificacion; elegibilidad responde el veredicto")
	void listar_y_elegibilidad() throws Exception {
		given(derivacionService.consultar(any(), anyLong(), anyLong(), anyLong(), any()))
				.willReturn(new EstadoDeDerivacionView(40L, List.of(derivacion(false))));
		given(derivacionService.elegibilidad(any(), anyLong(), anyLong(), anyLong()))
				.willReturn(new ElegibilidadClinicaView(INSCRIPCION_ID, 512L, 981L, false, false,
						"PRESENTE", true, false, false, List.of("OFERTA_NO_CLINICA")));

		autenticado(get(BASE + "/derivaciones")
				.header(DerivacionController.JUSTIFICACION, "Auditoria interna"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.historiaClinicaId").value(40))
				.andExpect(jsonPath("$.derivaciones[0].id").value(501));
		verify(derivacionService).consultar(any(), eq(SEDE_ID), eq(CLASE_ID), eq(INSCRIPCION_ID),
				eq("Auditoria interna"));

		autenticado(get(BASE + "/elegibilidad-clinica"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.derivable").value(false))
				.andExpect(jsonPath("$.motivos[0]").value("OFERTA_NO_CLINICA"));
	}

	@Test
	@DisplayName("Revertir exige motivo; una derivacion ajena a la participacion es 404")
	void revertir() throws Exception {
		autenticado(post(BASE + "/derivaciones/501/reversion")
				.contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\" \"}"))
				.andExpect(status().isBadRequest());
		verify(derivacionService, never()).revertir(any(), anyLong(), anyLong(), anyLong(),
				anyLong(), anyString(), any());

		given(derivacionService.revertir(any(), anyLong(), anyLong(), anyLong(), anyLong(),
				anyString(), any()))
				.willThrow(new DerivacionNoAccesibleException(501L));
		autenticado(post(BASE + "/derivaciones/501/reversion")
				.contentType(MediaType.APPLICATION_JSON).content("{\"motivo\":\"Caso equivocado\"}"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.type")
						.value(ProblemType.DERIVACION_NO_ACCESIBLE.uri().toString()));
	}

	// =================================================================================

	private ResultActions autenticado(MockHttpServletRequestBuilder peticion) throws Exception {
		return mockMvc.perform(peticion.with(authentication(new UsernamePasswordAuthenticationToken(
				new PrincipalDePrueba(CUENTA), "n/a", List.of()))));
	}

	private static DerivacionView derivacion(boolean yaExistia) {
		return new DerivacionView(501L, INSCRIPCION_ID, 981L, 512L, 40L, 88L, 3, null, 45L, false,
				null, "VIGENTE", "Indicacion", Instant.parse("2026-10-14T13:00:00Z"), CUENTA, null,
				null, null, 0L, yaExistia);
	}

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
