package com.akine.platform.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.akine.PerfilesDeTest.SOLO_SLICE;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifica el contrato de errores del proyecto (ADR-0005).
 *
 * <p>Lo que se prueba no es "que devuelva un error", sino las dos garantias que hacen
 * util ese contrato: que los errores de validacion lleguen accionables por campo, y que
 * <b>ninguna respuesta filtre detalles internos</b>.
 *
 * <p>Usa un controller de prueba porque el baseline todavia no tiene endpoints de negocio
 * que fallen. Vive en las fuentes de test, asi que los tests de arquitectura no lo ven
 * ({@code ImportOption.DoNotIncludeTests}).
 */
// El controller va en @Import, no en el atributo de @WebMvcTest: al ser una clase anidada
// del test no la alcanza el escaneo de componentes, y sin importarla el DispatcherServlet
// responde 404 de recurso estatico. Eso hacia que los tests que esperan 500 pasaran por el
// motivo equivocado.
@WebMvcTest(controllers = GlobalExceptionHandlerTest.ControllerDePrueba.class)
@Import({
		GlobalExceptionHandlerTest.ControllerDePrueba.class,
		GlobalExceptionHandler.class,
		GlobalExceptionHandlerTest.CadenaDeSlice.class})
@ActiveProfiles(SOLO_SLICE)
class GlobalExceptionHandlerTest {

	/**
	 * Cadena de seguridad propia del slice, permisiva.
	 *
	 * <p><b>Antes este test importaba {@code SecurityConfig}</b>, la cadena real de la
	 * aplicacion. Dejo de servir cuando AKINE-01.02 la cerro con {@code anyRequest()
	 * .authenticated()}: el controller de prueba vive en {@code /test/**}, que no es ni puede
	 * ser una ruta publica de la aplicacion, asi que los cuatro casos pasaron a responder 401 y
	 * el contrato de errores dejo de ejercitarse.
	 *
	 * <p>Es el mismo criterio que {@code ApiSliceSecurityConfig} en {@code organization.api}:
	 * un slice prueba el comportamiento HTTP de su pieza, no la cadena de la aplicacion.
	 * Heredarla lo ata a lo que esa clase permita en cada momento. Las respuestas 401 y 403 de
	 * la cadena real tienen sus propios tests en
	 * {@code com.akine.platform.security.SecurityChainTest}.
	 */
	@TestConfiguration
	@EnableWebSecurity
	static class CadenaDeSlice {

		@Bean
		SecurityFilterChain sliceSecurityFilterChain(HttpSecurity http) throws Exception {
			http
					.csrf(csrf -> csrf.disable())
					.sessionManagement(session -> session
							.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
					.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
			return http.build();
		}
	}

	@Autowired
	private MockMvc mockMvc;

	@Test
	@DisplayName("Una validacion fallida devuelve 400 con el detalle por campo")
	void validacion_fallida_detalla_los_campos() throws Exception {
		mockMvc.perform(post("/test/validar")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nombre\":\"\",\"email\":\"no-es-un-email\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Error de validacion"))
				.andExpect(jsonPath("$.status").value(400))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"))
				// El cliente necesita saber QUE campo rechazar para pintar el formulario.
				.andExpect(jsonPath("$.errors.nombre").exists())
				.andExpect(jsonPath("$.errors.email").exists());
	}

	@Test
	@DisplayName("Una excepcion inesperada devuelve 500 generico")
	void excepcion_inesperada_responde_generico() throws Exception {
		mockMvc.perform(post("/test/explotar")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.title").value("Error interno"))
				.andExpect(jsonPath("$.status").value(500))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/internal-error"))
				.andExpect(jsonPath("$.detail").value("Ocurrio un error inesperado"));
	}

	@Test
	@DisplayName("Ninguna respuesta de error filtra detalles internos")
	void los_errores_no_filtran_internals() throws Exception {
		String cuerpo = mockMvc.perform(post("/test/explotar")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isInternalServerError())
				.andReturn()
				.getResponse()
				.getContentAsString();

		// El mensaje de la excepcion nombra una tabla a proposito: si se filtrara, un
		// atacante obtendria el mapa interno de la aplicacion.
		org.assertj.core.api.Assertions.assertThat(cuerpo)
				.doesNotContain("paciente_historia_clinica")
				.doesNotContain("com.akine")
				.doesNotContain("java.lang")
				.doesNotContain("Exception");
	}

	@Test
	@DisplayName("Un JSON sintacticamente roto es 400, no 500")
	void json_roto_es_400() throws Exception {
		mockMvc.perform(post("/test/validar")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nombre\": \"Ana\", "))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Cuerpo invalido"))
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/validation-error"));
	}

	@Test
	@DisplayName("Un campo con el tipo equivocado es 400, y no repite lo recibido")
	void tipo_equivocado_en_el_cuerpo_es_400() throws Exception {
		String cuerpo = mockMvc.perform(post("/test/validar")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nombre\":{\"inyectado\":\"<script>\"},\"email\":\"a@b.test\"}"))
				.andExpect(status().isBadRequest())
				.andReturn()
				.getResponse()
				.getContentAsString();

		// Lo que llego en el request no se refleja: es el vector clasico de XSS reflejado, y
		// ademas el mensaje crudo de Jackson nombra la clase Java que se estaba deserializando.
		org.assertj.core.api.Assertions.assertThat(cuerpo)
				.doesNotContain("inyectado")
				.doesNotContain("script")
				.doesNotContain("com.akine");
	}

	@Test
	@DisplayName("Un Content-Type que el endpoint no consume es 415, no 500")
	void content_type_no_soportado_es_415() throws Exception {
		String cuerpo = mockMvc.perform(post("/test/validar")
						.contentType(MediaType.TEXT_PLAIN)
						.content("hola"))
				.andExpect(status().isUnsupportedMediaType())
				.andExpect(jsonPath("$.status").value(415))
				.andExpect(jsonPath("$.title").value("Solicitud no procesable"))
				.andReturn()
				.getResponse()
				.getContentAsString();

		org.assertj.core.api.Assertions.assertThat(cuerpo).doesNotContain("text/plain");
	}

	@Test
	@DisplayName("Un metodo no permitido en la ruta es 405, no 500")
	void metodo_no_permitido_es_405() throws Exception {
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
						.delete("/test/validar"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.status").value(405));
	}

	// =================================================================================
	// DP-21 — la concurrencia sale siempre como concurrent-modification
	// =================================================================================

	@Test
	@DisplayName("DP-21: una version comparada a mano que quedo vieja es 409 concurrent-modification")
	void version_vieja_comparada_a_mano_es_concurrent_modification() throws Exception {
		String cuerpo = mockMvc.perform(post("/test/version-vieja"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/concurrent-modification"))
				.andExpect(jsonPath("$.title").value("Modificacion concurrente"))
				.andExpect(jsonPath("$.detail").value(GlobalExceptionHandler.CONCURRENT_MODIFICATION_DETAIL))
				.andReturn().getResponse().getContentAsString();

		// El mensaje de la excepcion trae ids y versiones internas: va al log, no al cliente.
		org.assertj.core.api.Assertions.assertThat(cuerpo).doesNotContain("version 2 contra 3");
	}

	@Test
	@DisplayName("DP-21: el @Version de JPA sale con el MISMO type que la version comparada a mano")
	void version_de_jpa_es_concurrent_modification() throws Exception {
		String cuerpo = mockMvc.perform(post("/test/version-jpa"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/concurrent-modification"))
				.andExpect(jsonPath("$.detail").value(GlobalExceptionHandler.CONCURRENT_MODIFICATION_DETAIL))
				.andReturn().getResponse().getContentAsString();

		org.assertj.core.api.Assertions.assertThat(cuerpo).doesNotContain("com.akine");
	}

	@Test
	@DisplayName("DP-21: el detalle dice que otra persona lo modifico y que hay que recargar")
	void el_detalle_pide_recargar() {
		org.assertj.core.api.Assertions.assertThat(GlobalExceptionHandler.CONCURRENT_MODIFICATION_DETAIL)
				.contains("Otra persona modifico")
				.contains("Vuelva a cargarlo");
	}

	@Test
	@DisplayName("DP-21: un choque de unicidad NO es concurrencia: sigue siendo conflict")
	void choque_de_unicidad_sigue_siendo_conflict() throws Exception {
		mockMvc.perform(post("/test/unique"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.type").value("https://akine.app/problems/conflict"));
	}

	@Test
	@DisplayName("Un cuerpo valido pasa sin tocar el handler")
	void cuerpo_valido_no_dispara_el_handler() throws Exception {
		mockMvc.perform(post("/test/validar")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"nombre\":\"Ana\",\"email\":\"ana@ejemplo.test\"}"))
				.andExpect(status().isOk())
				.andExpect(content().string("ok"));
	}

	// =================================================================================
	// Andamiaje de prueba
	// =================================================================================

	record CuerpoDePrueba(
			@NotBlank(message = "el nombre es obligatorio") String nombre,
			@Email(message = "debe ser una direccion valida") String email) {
	}

	@RestController
	@Profile(SOLO_SLICE)
	@RequestMapping("/test")
	static class ControllerDePrueba {

		@PostMapping("/validar")
		String validar(@Valid @RequestBody CuerpoDePrueba cuerpo) {
			return "ok";
		}

		@PostMapping("/version-vieja")
		String versionVieja() {
			throw new org.springframework.dao.OptimisticLockingFailureException(
					"La sede 7 cambio desde que se leyo: version 2 contra 3");
		}

		@PostMapping("/version-jpa")
		String versionJpa() {
			throw new org.springframework.orm.ObjectOptimisticLockingFailureException(
					"com.akine.Turno", 7L);
		}

		@PostMapping("/unique")
		String unique() {
			throw new org.springframework.dao.DataIntegrityViolationException(
					"Duplicate entry for key uk_consultorio_nombre");
		}

		@PostMapping("/explotar")
		String explotar() {
			throw new IllegalStateException(
					"detalle interno que jamas debe salir: tabla paciente_historia_clinica");
		}
	}
}
