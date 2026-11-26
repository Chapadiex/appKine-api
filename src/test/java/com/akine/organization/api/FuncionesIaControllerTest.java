package com.akine.organization.api;

import com.akine.organization.application.AuthorizationGuard;
import com.akine.organization.application.FuncionesIaService;
import com.akine.organization.application.FuncionesIaView;
import com.akine.organization.application.TextoDelConsentimientoIa;
import com.akine.organization.domain.exception.OrganizationNotFoundException;
import com.akine.organization.spi.FuncionIa;
import com.akine.platform.spi.modelo.MotorDeModelos;
import com.akine.platform.spi.modelo.agente.ConfiguracionDelAgente;
import com.akine.platform.spi.tenant.TenantContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Capa HTTP de las funciones de IA del centro: leer alcanza con ser miembro, cambiar y tocar el
 * motor exige editor, y la URL interna de Ollama no sale.
 */
@WebMvcTest(FuncionesIaController.class)
@Import(ApiSliceSecurityConfig.class)
class FuncionesIaControllerTest {

	private static final String URL = "/api/v1/organizations/7/ia";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private FuncionesIaService funcionesIaService;

	@MockitoBean
	private MotorDeModelos motor;

	@MockitoBean
	private ConfiguracionDelAgente configuracionDelAgente;

	@MockitoBean
	private AuthorizationGuard authorizationGuard;

	@MockitoBean
	private TenantContextHolder tenantContextHolder;

	@MockitoBean
	private TextoDelConsentimientoIa textoDelConsentimiento;

	@Test
	@DisplayName("Leer alcanza con ser miembro y trae las cuatro funciones con sus dos capas")
	void lectura_para_cualquier_miembro() throws Exception {
		given(funcionesIaService.ver(7L)).willReturn(vista(2L));

		mockMvc.perform(get(URL).with(ApiActors.miembro(42L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(2))
				.andExpect(jsonPath("$.funciones.length()").value(4))
				.andExpect(jsonPath("$.funciones[0].funcion").value("ASISTENTE"))
				.andExpect(jsonPath("$.funciones[0].centro").value(false))
				.andExpect(jsonPath("$.funciones[0].activa").value(false))
				.andExpect(jsonPath("$.funciones[1].despliegue").value(true));

		verify(authorizationGuard).requireMember(eq(42L), eq(7L), any(), anyBoolean());
	}

	@Test
	@DisplayName("Otro tenant responde 404 y no se lee nada")
	void otro_tenant_es_404() throws Exception {
		willThrow(new OrganizationNotFoundException(7L))
				.given(authorizationGuard).requireMember(anyLong(), anyLong(), any(), anyBoolean());

		mockMvc.perform(get(URL).with(ApiActors.miembro(42L))).andExpect(status().isNotFound());
		verify(funcionesIaService, never()).ver(anyLong());
	}

	@Test
	@DisplayName("Cambiar pasa los cuatro interruptores, la version y el actor")
	void actualizar_con_version_y_actor() throws Exception {
		given(funcionesIaService.actualizar(7L, false, true, true, true, 2L, 42L)).willReturn(vista(3L));

		mockMvc.perform(put(URL).with(ApiActors.miembro(42L))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"asistente":false,"evolucion":true,"sugerencias":true,"biblioteca":true,"version":2}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value(3));

		verify(authorizationGuard).requireOrgDataEditor(eq(42L), eq(7L), any(), anyBoolean());
	}

	@Test
	@DisplayName("Sin un interruptor o sin version es 400 y no se guarda")
	void actualizar_incompleto_es_400() throws Exception {
		mockMvc.perform(put(URL).with(ApiActors.miembro(42L))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"asistente":false,"evolucion":true}"""))
				.andExpect(status().isBadRequest());

		verify(funcionesIaService, never())
				.actualizar(anyLong(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), anyLong(), any());
	}

	@Test
	@DisplayName("Quien no edita la organizacion no toca el motor")
	void motor_exige_editor() throws Exception {
		willThrow(new AccessDeniedException("no"))
				.given(authorizationGuard).requireOrgDataEditor(anyLong(), anyLong(), any(), anyBoolean());

		mockMvc.perform(post(URL + "/motor/prueba").with(ApiActors.miembro(42L))).andExpect(status().isForbidden());
		verify(motor, never()).probar(any());
	}

	@Test
	@DisplayName("El estado del motor sale sin la URL interna, con GPU o CPU por modelo")
	void estado_del_motor_sin_url() throws Exception {
		given(motor.estado(any())).willReturn(new MotorDeModelos.EstadoDelMotor(true, "http://ollama:11434", null,
				List.of(new MotorDeModelos.ModeloDelMotor("qwen3:4b-instruct", List.of("ASISTENTE"), true, true,
						3600, 3600))));

		mockMvc.perform(get(URL + "/motor").with(ApiActors.miembro(42L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.alcanzable").value(true))
				.andExpect(jsonPath("$.url").doesNotExist())
				.andExpect(jsonPath("$.modelos[0].ejecucion").value("GPU"));
	}

	@Test
	@DisplayName("Precargar y probar devuelven lo que dice el motor")
	void precarga_y_prueba() throws Exception {
		given(motor.precargar(any())).willReturn(new MotorDeModelos.EstadoDelMotor(false, null, "no responde",
				List.of()));
		given(motor.probar(any())).willReturn(new MotorDeModelos.PruebaDelMotor(true, "qwen3:4b-instruct", 850, null));

		mockMvc.perform(post(URL + "/motor/precarga").with(ApiActors.miembro(42L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.alcanzable").value(false))
				.andExpect(jsonPath("$.error").value("no responde"));
		mockMvc.perform(post(URL + "/motor/prueba").with(ApiActors.miembro(42L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(true))
				.andExpect(jsonPath("$.milisegundos").value(850));
	}

	@Test
	@DisplayName("El texto del consentimiento se lee siendo miembro, con su version")
	void texto_del_consentimiento() throws Exception {
		given(textoDelConsentimiento.version()).willReturn("2026-11-26");
		given(textoDelConsentimiento.texto()).willReturn("El centro es responsable.");

		mockMvc.perform(get(URL + "/consentimiento").with(ApiActors.miembro(42L)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.version").value("2026-11-26"))
				.andExpect(jsonPath("$.texto").value("El centro es responsable."));

		verify(authorizationGuard).requireMember(eq(42L), eq(7L), any(), anyBoolean());
	}

	@Test
	@DisplayName("Aceptar exige editor y devuelve quien acepto")
	void aceptar_consentimiento() throws Exception {
		given(funcionesIaService.aceptarConsentimiento(7L, "2026-11-26", 42L)).willReturn(vista(2L));

		mockMvc.perform(post(URL + "/consentimiento").with(ApiActors.miembro(42L))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"version":"2026-11-26"}"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.consentimiento.aceptado").value(true))
				.andExpect(jsonPath("$.consentimiento.aceptadoPor").value("Ana Gomez"));

		verify(authorizationGuard).requireOrgDataEditor(eq(42L), eq(7L), any(), anyBoolean());
	}

	@Test
	@DisplayName("Aceptar una version que ya no es la vigente es 409")
	void aceptar_version_vieja_es_409() throws Exception {
		willThrow(new OptimisticLockingFailureException("cambio"))
				.given(funcionesIaService).aceptarConsentimiento(anyLong(), any(), anyLong());

		mockMvc.perform(post(URL + "/consentimiento").with(ApiActors.miembro(42L))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"version":"2026-01-01"}"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value(org.hamcrest.Matchers.endsWith("concurrent-modification")));
	}

	private static FuncionesIaView vista(long version) {
		return new FuncionesIaView(7L, version, List.of(
				new FuncionesIaView.Funcion(FuncionIa.ASISTENTE, true, false, false),
				new FuncionesIaView.Funcion(FuncionIa.EVOLUCION, true, true, true),
				new FuncionesIaView.Funcion(FuncionIa.SUGERENCIAS, false, true, false),
				new FuncionesIaView.Funcion(FuncionIa.BIBLIOTECA, true, true, true)),
				new FuncionesIaView.Consentimiento(true, "2026-11-26", true, "Ana Gomez", Instant.parse("2026-11-26T13:00:00Z")));
	}
}
