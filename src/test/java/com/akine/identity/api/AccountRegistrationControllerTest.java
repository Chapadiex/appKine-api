package com.akine.identity.api;

import com.akine.identity.application.AccountActivationService;
import com.akine.identity.application.OnboardingService;
import com.akine.identity.application.RegistroCuentaCommand;
import com.akine.identity.application.ResultadoRegistro;
import com.akine.identity.domain.exception.InvalidVerificationTokenException;
import com.akine.identity.domain.exception.PasswordPolicyViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP del alta self-service y la activacion (RF-M02-001, ADR-0018).
 *
 * <p>El test central de esta clase es
 * {@link #el_registro_responde_identico_exista_o_no_la_cuenta()}: compara byte a byte las dos
 * respuestas. Si alguien alguna vez agrega un {@code Location}, un id o un 409, ese test se
 * pone rojo, que es exactamente para lo que esta.
 */
@WebMvcTest(AccountRegistrationController.class)
@Import(IdentityApiSliceSecurityConfig.class)
class AccountRegistrationControllerTest {

	private static final String CLAVE = "0f9d5f6e-1c2b-4a3d-9e8f-7a6b5c4d3e2f";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OnboardingService onboardingService;

	@MockitoBean
	private AccountActivationService activationService;

	private static String altaValida() {
		return """
				{"email":"ana.gomez@ejemplo.test","password":"kinesiologia-2026",\
				"firstName":"Ana","lastName":"Gomez",\
				"organizationName":"Centro Kinesico Belgrano"}""";
	}

	// =================================================================================
	// Registro
	// =================================================================================

	@Test
	@DisplayName("el alta responde 202 y traduce el request al comando de aplicacion")
	void el_alta_responde_202_y_traduce_el_comando() throws Exception {
		given(onboardingService.registrar(any()))
				.willReturn(new ResultadoRegistro(true, 100L, 10L, 20L));

		mockMvc.perform(post("/api/v1/auth/register")
						.header("Idempotency-Key", CLAVE)
						.contentType(MediaType.APPLICATION_JSON)
						.content(altaValida()))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.message").isNotEmpty());

		ArgumentCaptor<RegistroCuentaCommand> comando =
				ArgumentCaptor.forClass(RegistroCuentaCommand.class);
		verify(onboardingService).registrar(comando.capture());
		assertThat(comando.getValue().claveIdempotencia()).isEqualTo(CLAVE);
		assertThat(comando.getValue().email()).isEqualTo("ana.gomez@ejemplo.test");
		assertThat(comando.getValue().nombre()).isEqualTo("Ana");
		assertThat(comando.getValue().organizacionNombre()).isEqualTo("Centro Kinesico Belgrano");
	}

	@Test
	@DisplayName("el registro responde IDENTICO con email libre y con email ya tomado")
	void el_registro_responde_identico_exista_o_no_la_cuenta() throws Exception {
		given(onboardingService.registrar(any()))
				.willReturn(new ResultadoRegistro(true, 100L, 10L, 20L));

		MvcResult libre = mockMvc.perform(post("/api/v1/auth/register")
						.header("Idempotency-Key", CLAVE)
						.contentType(MediaType.APPLICATION_JSON)
						.content(altaValida()))
				.andExpect(status().isAccepted())
				.andReturn();

		// Mismo request, pero el servicio informa que no creo nada porque el email ya existia.
		given(onboardingService.registrar(any())).willReturn(new ResultadoRegistro(false, null, null, null));

		MvcResult tomado = mockMvc.perform(post("/api/v1/auth/register")
						.header("Idempotency-Key", CLAVE)
						.contentType(MediaType.APPLICATION_JSON)
						.content(altaValida()))
				.andExpect(status().isAccepted())
				.andReturn();

		assertThat(tomado.getResponse().getStatus()).isEqualTo(libre.getResponse().getStatus());
		assertThat(tomado.getResponse().getContentAsString())
				.isEqualTo(libre.getResponse().getContentAsString());
		assertThat(tomado.getResponse().getHeaderNames())
				.containsExactlyInAnyOrderElementsOf(libre.getResponse().getHeaderNames());
		assertThat(libre.getResponse().getHeader("Location"))
				.as("un Location con el id de la cuenta delataria que se creo")
				.isNull();
		assertThat(libre.getResponse().getContentAsString())
				.as("ningun identificador del desenlace puede salir en el cuerpo")
				.doesNotContain("100").doesNotContain("10").doesNotContain("20");
	}

	@Test
	@DisplayName("sin Idempotency-Key el alta es 400 y no llega al servicio")
	void sin_clave_de_idempotencia_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.contentType(MediaType.APPLICATION_JSON)
						.content(altaValida()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"));

		verify(onboardingService, never()).registrar(any());
	}

	@Test
	@DisplayName("una clave de idempotencia en blanco tambien es 400")
	void una_clave_en_blanco_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.header("Idempotency-Key", "   ")
						.contentType(MediaType.APPLICATION_JSON)
						.content(altaValida()))
				.andExpect(status().isBadRequest());

		verify(onboardingService, never()).registrar(any());
	}

	@Test
	@DisplayName("un email con formato invalido es 400 y no toca la base")
	void un_email_invalido_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/register")
						.header("Idempotency-Key", CLAVE)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"no-es-un-email","password":"kinesiologia-2026",\
								"firstName":"Ana","lastName":"Gomez",\
								"organizationName":"Centro Kinesico Belgrano"}"""))
				.andExpect(status().isBadRequest());

		verify(onboardingService, never()).registrar(any());
	}

	@Test
	@DisplayName("una contrasena que no cumple la politica es 400 con el motivo explicito")
	void una_contrasena_debil_es_400_con_motivo() throws Exception {
		willThrow(new PasswordPolicyViolationException(
				"La contrasena debe tener al menos 10 caracteres"))
				.given(onboardingService).registrar(any());

		mockMvc.perform(post("/api/v1/auth/register")
						.header("Idempotency-Key", CLAVE)
						.contentType(MediaType.APPLICATION_JSON)
						.content(altaValida()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"))
				.andExpect(jsonPath("$.detail")
						.value("La contrasena debe tener al menos 10 caracteres"));
	}

	// =================================================================================
	// Activacion
	// =================================================================================

	@Test
	@DisplayName("activar consume el token y responde 204 sin devolver nada de la cuenta")
	void activar_responde_204_sin_datos_de_la_cuenta() throws Exception {
		MvcResult resultado = mockMvc.perform(post("/api/v1/auth/activate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"token":"token-del-correo"}"""))
				.andExpect(status().isNoContent())
				.andReturn();

		assertThat(resultado.getResponse().getContentAsString()).isEmpty();
		verify(activationService).activar("token-del-correo", null);
	}

	@Test
	@DisplayName("un token invalido, usado o vencido responde el mismo 400 invalid-token")
	void un_token_que_no_sirve_es_400_invalid_token() throws Exception {
		willThrow(new InvalidVerificationTokenException())
				.given(activationService).activar(anyString(), any());

		MvcResult vencido = mockMvc.perform(post("/api/v1/auth/activate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"token":"token-vencido"}"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/invalid-token"))
				.andReturn();

		MvcResult inventado = mockMvc.perform(post("/api/v1/auth/activate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"token":"token-que-nunca-existio"}"""))
				.andExpect(status().isBadRequest())
				.andReturn();

		assertThat(vencido.getResponse().getContentAsString())
				.isEqualTo(inventado.getResponse().getContentAsString());
	}

	@Test
	@DisplayName("activar sin token es 400 y no llega al servicio")
	void activar_sin_token_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/activate")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"token":"  "}"""))
				.andExpect(status().isBadRequest());

		verify(activationService, never()).activar(anyString(), any());
	}

	// =================================================================================
	// Reenvio
	// =================================================================================

	@Test
	@DisplayName("el reenvio responde IDENTICO para una cuenta que existe y una que no")
	void el_reenvio_responde_identico_exista_o_no_la_cuenta() throws Exception {
		// El servicio es void: por construccion no puede informarle a esta capa que paso, que
		// es lo que hace imposible que la respuesta varie.
		MvcResult existe = mockMvc.perform(post("/api/v1/auth/activation/resend")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"ana.gomez@ejemplo.test"}"""))
				.andExpect(status().isAccepted())
				.andReturn();

		MvcResult noExiste = mockMvc.perform(post("/api/v1/auth/activation/resend")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":"nadie@ejemplo.test"}"""))
				.andExpect(status().isAccepted())
				.andReturn();

		assertThat(noExiste.getResponse().getContentAsString())
				.isEqualTo(existe.getResponse().getContentAsString());
		assertThat(noExiste.getResponse().getHeaderNames())
				.containsExactlyInAnyOrderElementsOf(existe.getResponse().getHeaderNames());

		verify(activationService).reenviarActivacion("ana.gomez@ejemplo.test");
		verify(activationService).reenviarActivacion("nadie@ejemplo.test");
	}

	@Test
	@DisplayName("el reenvio sin email es 400")
	void el_reenvio_sin_email_es_400() throws Exception {
		mockMvc.perform(post("/api/v1/auth/activation/resend")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"email":""}"""))
				.andExpect(status().isBadRequest());

		verify(activationService, never()).reenviarActivacion(anyString());
	}
}
